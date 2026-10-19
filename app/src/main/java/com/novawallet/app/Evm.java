package com.novawallet.app;
import org.web3j.protocol.Web3j; import org.web3j.protocol.http.HttpService; import org.web3j.crypto.*; import org.web3j.utils.Convert;
import org.web3j.tx.RawTransactionManager; import org.web3j.protocol.core.methods.response.*; import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction; import org.web3j.abi.*; import org.web3j.abi.datatypes.*; import org.web3j.abi.datatypes.generated.*;
import java.math.*; import java.util.*;
public class Evm{
 static final java.util.Map<Long,Web3j> CACHE=new java.util.HashMap<>();
 public static Web3j web3(Networks.Net n){ return best(n); }
 public static Web3j best(Networks.Net n){ Web3j c=CACHE.get(n.chainId); if(c!=null)return c; Exception last=null;
   for(String rpc:n.rpcs){ try{ okhttp3.OkHttpClient ok=new okhttp3.OkHttpClient.Builder().connectTimeout(12,java.util.concurrent.TimeUnit.SECONDS).readTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build(); Web3j w=Web3j.build(new HttpService(rpc,ok,false)); w.ethBlockNumber().send(); CACHE.put(n.chainId,w); n.rpc=rpc; return w; }catch(Exception e){ last=e; } }
   // return first anyway so send path can surface its own error
   Web3j w=Web3j.build(new HttpService(n.rpcs[0])); CACHE.put(n.chainId,w); return w; }
 // Direct JSON-RPC (no Web3/Jackson on read path) — tries every RPC in turn
 static String rpcResult(Networks.Net n,String method,String params)throws Exception{ Exception last=null;
   for(String rpc:n.rpcs){ try{ String body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""+method+"\",\"params\":"+params+"}"; String resp=Http.post(rpc,body); org.json.JSONObject o=new org.json.JSONObject(resp); if(o.has("error"))throw new Exception(o.getJSONObject("error").optString("message")); n.rpc=rpc; return o.optString("result"); }catch(Exception e){ last=e; } }
   throw new Exception("Все RPC недоступны ("+(last==null?"?":last.getMessage())+"). Проверьте интернет/VPN и попробуйте ещё раз."); }
 public static BigDecimal balanceDirect(Networks.Net n,String addr)throws Exception{ String hex=rpcResult(n,"eth_getBalance","[\""+addr+"\",\"latest\"]"); BigInteger wei=new BigInteger(hex.replace("0x",""),16); return new BigDecimal(wei).divide(new BigDecimal("1000000000000000000")); }
 public static Credentials creds(BigInteger priv){ return Credentials.create(ECKeyPair.create(priv)); }
 public static BigDecimal balance(Networks.Net n,String addr) throws Exception{ return balanceDirect(n,addr); }

 // ---------- Надёжное управление nonce: pending-нонс + локальная очередь + автоповтор ----------
 static final java.util.Map<String,BigInteger> NONCES=new java.util.HashMap<>();
 interface TxBuilder{ RawTransaction build(BigInteger nonce)throws Exception; }
 static synchronized BigInteger nextNonce(Web3j w,Networks.Net n,String addr)throws Exception{
   BigInteger pending=w.ethGetTransactionCount(addr,DefaultBlockParameterName.PENDING).send().getTransactionCount();
   String key=n.chainId+":"+addr.toLowerCase(); BigInteger local=NONCES.get(key);
   BigInteger use=(local!=null&&local.compareTo(pending)>0)?local:pending;
   NONCES.put(key,use.add(BigInteger.ONE)); return use; }
 static synchronized void resyncNonce(Networks.Net n,String addr){ NONCES.remove(n.chainId+":"+addr.toLowerCase()); }
 static String execute(Networks.Net n,BigInteger priv,TxBuilder b)throws Exception{
   Web3j w=web3(n); Credentials c=creds(priv); Exception last=null;
   for(int attempt=0;attempt<3;attempt++){
     BigInteger nonce=nextNonce(w,n,c.getAddress());
     RawTransaction rt=b.build(nonce);
     byte[] signed=TransactionEncoder.signMessage(rt,n.chainId,c);
     EthSendTransaction tx=w.ethSendRawTransaction(org.web3j.utils.Numeric.toHexString(signed)).send();
     if(!tx.hasError())return tx.getTransactionHash();
     String msg=tx.getError().getMessage(); last=new Exception(msg);
     if(msg!=null&&(msg.toLowerCase().contains("nonce")||msg.toLowerCase().contains("already known")||msg.toLowerCase().contains("replacement"))){ resyncNonce(n,c.getAddress()); continue; }
     throw last; }
   throw new Exception("Сеть отклонила транзакцию три раза подряд. "+(last==null?"":last.getMessage())); }
 static BigInteger gasPrice(Web3j w)throws Exception{ return w.ethGasPrice().send().getGasPrice().multiply(BigInteger.valueOf(130)).divide(BigInteger.valueOf(100)).add(BigInteger.valueOf(100000000L)); } // +30% к газу — быстрее подтверждение
 static BigInteger estimate(Web3j w,String from,String to,BigInteger val,String data) { try{ EthEstimateGas e=w.ethEstimateGas(Transaction.createFunctionCallTransaction(from,null,null,null,to,val,data)).send(); if(!e.hasError())return e.getAmountUsed().multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100)).add(BigInteger.valueOf(5000)); }catch(Exception ignored){} return BigInteger.valueOf(200000); }
 public static String sendEth(Networks.Net n,BigInteger priv,String to,BigDecimal amt) throws Exception{ Web3j w=web3(n); Credentials c=creds(priv); BigInteger wei=Convert.toWei(amt,Convert.Unit.ETHER).toBigIntegerExact(); BigInteger gp=gasPrice(w);
   BigInteger gl=estimate(w,c.getAddress(),to,wei,"0x"); if(gl.compareTo(BigInteger.valueOf(50000))<0)gl=BigInteger.valueOf(50000); // запас: получатель может быть смарт-аккаунтом, неиспользованный газ не списывается
   final BigInteger glF=gl; return execute(n,priv,nonce->RawTransaction.createEtherTransaction(nonce,gp,glF,to,wei)); }
 public static String txFailureReason(Networks.Net n,String hash){ // человеческая причина Fail из Blockscout
   try{ String base=n.chainId==11155111?"https://eth-sepolia.blockscout.com":n.chainId==1?"https://eth.blockscout.com":null; if(base==null)return null;
     String r=Http.get(base+"/api/v2/transactions/"+hash); org.json.JSONObject o=new org.json.JSONObject(r); String res=o.optString("result",""); org.json.JSONObject rr=o.optJSONObject("revert_reason"); String reason=rr!=null?rr.optString("raw",""):"";
     if("Out of gas".equals(res))return "Не хватило газа: лимит закончился во время перевода. В новой версии лимит считается автоматически с запасом — просто отправьте ещё раз после обновления.";
     if(reason!=null&&!reason.isEmpty())return "Контракт отменил операцию: "+reason;
     return null; }catch(Exception e){ return null; } }
 static String callStr(Networks.Net n,String from,String contract,Function f)throws Exception{ String data=FunctionEncoder.encode(f); EthCall c=web3(n).ethCall(Transaction.createEthCallTransaction(from,contract,data),DefaultBlockParameterName.LATEST).send(); if(c.hasError())throw new Exception(c.getError().getMessage()); List<Type> out=FunctionReturnDecoder.decode(c.getValue(),f.getOutputParameters()); return out.isEmpty()?"":String.valueOf(out.get(0).getValue()); }
 public static String erc20Info(Networks.Net n,String addr,String contract,int which)throws Exception{ String fn=which==0?"name":which==1?"symbol":"decimals"; TypeReference<?> ref=which==2?new TypeReference<Uint8>(){}:new TypeReference<Utf8String>(){}; Function f=new Function(fn,Collections.emptyList(),Arrays.asList(ref)); return callStr(n,addr,contract,f); }
 public static BigDecimal erc20Balance(Networks.Net n,String addr,String contract,int dec)throws Exception{ String data="0x70a08231000000000000000000000000"+addr.replace("0x","").toLowerCase(); String v=rpcResult(n,"eth_call","[{\"to\":\""+contract+"\",\"data\":\""+data+"\"},\"latest\"]"); BigInteger raw=v==null||v.length()<=2?BigInteger.ZERO:new BigInteger(v.replace("0x",""),16); return new BigDecimal(raw).divide(BigDecimal.TEN.pow(dec)); }
 public static String sendErc20(Networks.Net n,BigInteger priv,String contract,String to,BigDecimal amt,int dec)throws Exception{ Web3j w=web3(n); Credentials c=creds(priv); BigInteger raw=amt.multiply(BigDecimal.TEN.pow(dec)).toBigIntegerExact(); Function f=new Function("transfer",Arrays.asList(new Address(to),new Uint256(raw)),Collections.emptyList()); String data=FunctionEncoder.encode(f); BigInteger gl=estimate(w,c.getAddress(),contract,BigInteger.ZERO,data); BigInteger gp=gasPrice(w); return execute(n,priv,nonce->RawTransaction.createTransaction(nonce,gp,gl,contract,BigInteger.ZERO,data)); }
 public static String deployToken(Networks.Net n,BigInteger priv,String name,String symbol,int dec,BigInteger supply)throws Exception{ Web3j w=web3(n); Credentials c=creds(priv);
   String enc=encodeCtor(name,symbol,dec,supply); String data=Contracts.ERC20_BYTECODE+enc; BigInteger gl=estimate(w,c.getAddress(),null,BigInteger.ZERO,"0x"+data); if(gl.compareTo(BigInteger.valueOf(300000))<0)gl=BigInteger.valueOf(3000000);
   BigInteger gp=gasPrice(w); final BigInteger glF=gl; String hash=execute(n,priv,nonce->RawTransaction.createContractTransaction(nonce,gp,glF,BigInteger.ZERO,data));
   for(int i=0;i<40;i++){ Thread.sleep(3000); EthGetTransactionReceipt r=w.ethGetTransactionReceipt(hash).send(); if(r.getTransactionReceipt().isPresent()&&r.getTransactionReceipt().get().getContractAddress()!=null)return hash+"|"+r.getTransactionReceipt().get().getContractAddress(); } return hash+"|pending"; }
 static String encodeCtor(String name,String sym,int dec,BigInteger supply){ byte[] n=name.getBytes(java.nio.charset.StandardCharsets.UTF_8), s=sym.getBytes(java.nio.charset.StandardCharsets.UTF_8); StringBuilder sb=new StringBuilder(); sb.append(padNum(BigInteger.valueOf(128))).append(padNum(BigInteger.valueOf(128+32+pad32(n.length)))).append(padNum(BigInteger.valueOf(dec))).append(padNum(supply)); sb.append(padNum(BigInteger.valueOf(n.length))).append(padBytes(n)); sb.append(padNum(BigInteger.valueOf(s.length))).append(padBytes(s)); return sb.toString(); }
 static int pad32(int len){return ((len+31)/32)*32;}
 static String padNum(BigInteger v){return String.format("%064x",v);}
 static String padBytes(byte[] b){ StringBuilder sb=new StringBuilder(Util.hex(b)); while(sb.length()<pad32(b.length)*2)sb.append("00"); return sb.toString(); }
 public static String sendData(Networks.Net n,BigInteger priv,String to,byte[] data)throws Exception{ return sendData(n,priv,to,data,BigInteger.ZERO); }
 public static String sendData(Networks.Net n,BigInteger priv,String to,byte[] data,BigInteger valueWei)throws Exception{ Web3j w=web3(n); Credentials c=creds(priv); String dataHex=org.web3j.utils.Numeric.toHexString(data); BigInteger gl; try{ gl=estimate(w,c.getAddress(),to,valueWei,dataHex); }catch(Exception e){ gl=BigInteger.ZERO; } BigInteger min=BigInteger.valueOf(45000+20L*data.length); if(gl.compareTo(min)<0)gl=min; BigInteger gp=gasPrice(w); final BigInteger glF=gl; return execute(n,priv,nonce->RawTransaction.createTransaction(nonce,gp,glF,to,valueWei,dataHex)); }
 public static String receiptStatus(Networks.Net n,String hash){ try{ String r=rpcResult(n,"eth_getTransactionReceipt","[\""+hash+"\"]"); if(r==null||r.equals("null")||r.isEmpty())return null; org.json.JSONObject o=new org.json.JSONObject(r); String st=o.optString("status",""); String blk=o.optString("blockNumber",""); return ("0x1".equals(st)?"confirmed":"failed")+"|"+blk; }catch(Exception e){ return null; } }
 public static String nftBalance(Networks.Net n,String addr,String contract)throws Exception{ Function f=new Function("balanceOf",Arrays.asList(new Address(addr)),Arrays.asList(new TypeReference<Uint256>(){})); return callStr(n,addr,contract,f); }
 public static double priceUsd(String coingeckoId) { try{ String j=Http.get("https://api.coingecko.com/api/v3/simple/price?ids="+coingeckoId+"&vs_currencies=usd"); org.json.JSONObject o=new org.json.JSONObject(j).getJSONObject(coingeckoId); return o.optDouble("usd",0);}catch(Exception e){return 0;} }
}
