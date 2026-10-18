package com.novawallet.app;
import org.json.*; import java.math.*; import java.util.*; import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters; import org.bouncycastle.crypto.signers.Ed25519Signer;
public class BtcSol{
 public static BigDecimal btcBalance(String addr){ try{ String j=Http.get("https://mempool.space/api/address/"+addr); JSONObject o=new JSONObject(j); long funded=o.getJSONObject("chain_stats").optLong("funded_txo_sum"), spent=o.getJSONObject("chain_stats").optLong("spent_txo_sum"); return new BigDecimal(funded-spent).divide(new BigDecimal("100000000")); }catch(Exception e){return BigDecimal.ZERO;} }
 public static String btcSend(BigInteger priv,String from,String to,BigDecimal amount)throws Exception{
   String uj=Http.get("https://mempool.space/api/address/"+from+"/utxo"); JSONArray utxos=new JSONArray(uj);
   long target=amount.multiply(new BigDecimal("100000000")).longValueExact(); long feeRate=5; // sat/vB approx fee estimated later by size
   List<JSONObject> ins=new ArrayList<>(); long sum=0; for(int i=0;i<utxos.length()&&sum<target;i++){JSONObject u=utxos.getJSONObject(i); ins.add(u); sum+=u.getLong("value"); }
   long estFee=(180L*ins.size()+80)*feeRate; if(sum<target+estFee)throw new Exception("Недостаточно BTC (нужно с учётом комиссии ~"+estFee+" sat)");
   long change=sum-target-estFee;
   // Build BIP143 P2WPKH tx v2
   java.io.ByteArrayOutputStream raw=new java.io.ByteArrayOutputStream(); // placeholder replaced below
   byte[] tx=buildBtcTx(priv,from,ins,to,target,change);
   String hex=Util.hex(tx); String res=Http.postText("https://mempool.space/api/tx",hex);
   return res.trim().length()>=10?res.trim():hex;
 }
 static byte[] varint(long v){ if(v<253)return new byte[]{(byte)v}; if(v<65536)return new byte[]{(byte)253,(byte)v,(byte)(v>>>8)}; return new byte[]{(byte)254,(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)}; }
 static byte[] le32(long v){return new byte[]{(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)};} static byte[] le64(long v){byte[] b=new byte[8]; for(int i=0;i<8;i++)b[i]=(byte)(v>>>(8*i)); return b;}
 static byte[] reverse(byte[] b){byte[] o=b.clone(); for(int i=0;i<b.length;i++)o[i]=b[b.length-1-i]; return o;}
 static byte[] buildBtcTx(BigInteger priv,String from,List<JSONObject> ins,String to,long target,long change)throws Exception{
   byte[] myPub=WalletCore.pubCompressed(priv); byte[] h160=WalletCore.ripemd160(Util.sha256(myPub)); byte[] myScript=Util.concat(new byte[]{0x00,0x14},h160);
   byte[] toScript=addressToScript(to);
   // outputs
   List<byte[]> outScripts=new ArrayList<>(); List<Long> outVals=new ArrayList<>(); outVals.add(target); outScripts.add(toScript); if(change>546){outVals.add(change); outScripts.add(myScript);}
   // hashPrevouts, hashSequence, hashOutputs (BIP143)
   java.io.ByteArrayOutputStream hp=new java.io.ByteArrayOutputStream(), hs=new java.io.ByteArrayOutputStream(), ho=new java.io.ByteArrayOutputStream();
   for(JSONObject u:ins){ hp.write(reverse(Util.hex(u.getString("txid")))); hp.write(le32(u.getLong("vout"))); hs.write(le32(0xffffffffL)); }
   for(int i=0;i<outVals.size();i++){ ho.write(le64(outVals.get(i))); ho.write(varint(outScripts.get(i).length)); ho.write(outScripts.get(i)); }
   byte[] hashPrev=Util.sha256d(hp.toByteArray()), hashSeq=Util.sha256d(hs.toByteArray()), hashOut=Util.sha256d(ho.toByteArray());
   // sign each input
   List<byte[]> sigs=new ArrayList<>(), pubs=new ArrayList<>();
   for(int idx=0;idx<ins.size();idx++){ JSONObject u=ins.get(idx);
     java.io.ByteArrayOutputStream pre=new java.io.ByteArrayOutputStream(); pre.write(le32(2)); pre.write(hashPrev); pre.write(hashSeq); pre.write(reverse(Util.hex(u.getString("txid")))); pre.write(le32(u.getLong("vout")));
     byte[] scriptCode=Util.concat(new byte[]{(byte)0x19,0x76,(byte)0xa9,0x14},h160,new byte[]{(byte)0x88,(byte)0xac}); pre.write(scriptCode); pre.write(le64(u.getLong("value"))); pre.write(le32(0xffffffffL)); pre.write(hashOut); pre.write(le32(1));
     byte[] sighash=Util.sha256d(pre.toByteArray()); BigInteger[] sig=WalletCore.signSecp(priv,sighash); byte[] der=der(sig[0],sig[1]); sigs.add(Util.concat(der,new byte[]{1})); pubs.add(myPub); }
   java.io.ByteArrayOutputStream tx=new java.io.ByteArrayOutputStream(); tx.write(le32(2)); tx.write(new byte[]{0,1}); tx.write(varint(ins.size()));
   for(int i=0;i<ins.size();i++){ JSONObject u=ins.get(i); tx.write(reverse(Util.hex(u.getString("txid")))); tx.write(le32(u.getLong("vout"))); tx.write(varint(0)); tx.write(le32(0xffffffffL)); }
   tx.write(varint(outVals.size())); for(int i=0;i<outVals.size();i++){ tx.write(le64(outVals.get(i))); tx.write(varint(outScripts.get(i).length)); tx.write(outScripts.get(i)); }
   for(int i=0;i<ins.size();i++){ tx.write(varint(2)); tx.write(varint(sigs.get(i).length)); tx.write(sigs.get(i)); tx.write(varint(pubs.get(i).length)); tx.write(pubs.get(i)); }
   tx.write(le32(0)); return tx.toByteArray(); }
 static byte[] der(BigInteger r,BigInteger s){ byte[] rb=r.toByteArray(), sb=s.toByteArray(); java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream(); o.write(0x30); o.write(4+rb.length+sb.length+2-2); // length computed below correctly via rebuild
   // rebuild properly
   java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream(); body.write(2); body.write(rb.length); body.write(rb); body.write(2); body.write(sb.length); body.write(sb); byte[] b=body.toByteArray(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); out.write(0x30); out.write(b.length); out.write(b); return out.toByteArray(); }
 static byte[] addressToScript(String addr){ // bc1q P2WPKH only (own + most modern)
   // decode bech32 data part crudely via WalletCore convert? support bc1q: strip, map chars, drop checksum 6, drop version, convert 5->8
   String cs="qpzry9x8gf2tvdw0s3jn54khce6mua7l"; String data=addr.substring(addr.indexOf('1')+1); int[] vals=new int[data.length()-6]; for(int i=0;i<vals.length;i++)vals[i]=cs.indexOf(data.charAt(i)); int[] prog5=Arrays.copyOfRange(vals,1,vals.length); int[] prog=WalletCore.convertBits(prog5,5,8,false); byte[] p=new byte[prog.length]; for(int i=0;i<prog.length;i++)p[i]=(byte)prog[i]; return Util.concat(new byte[]{0x00,(byte)p.length},p); }
 // SOLANA
 public static BigDecimal solBalance(String addr){ try{ String j=Http.post("https://api.mainnet-beta.solana.com","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getBalance\",\"params\":[\""+addr+"\"]}"); JSONObject o=new JSONObject(j); long lam=o.getJSONObject("result").optLong("value"); return new BigDecimal(lam).divide(new BigDecimal("1000000000")); }catch(Exception e){return BigDecimal.ZERO;} }
 public static String solSend(byte[] seed,String from,String to,BigDecimal amt)throws Exception{
   String bj=Http.post("https://api.mainnet-beta.solana.com","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getLatestBlockhash\",\"params\":[{\"commitment\":\"finalized\"}]}"); String bh=new JSONObject(bj).getJSONObject("result").getJSONObject("value").getString("blockhash");
   long lam=amt.multiply(new BigDecimal("1000000000")).longValueExact();
   byte[] fromPk=Util.base58decode(from), toPk=Util.base58decode(to), prog=new byte[32], bhb=Util.base58decode(bh);
   // Message: header(3) + accounts(3: from,to,system) + blockhash + 1 instruction
   java.io.ByteArrayOutputStream msg=new java.io.ByteArrayOutputStream(); msg.write(new byte[]{1,0,1}); msg.write(3); msg.write(fromPk); msg.write(toPk); msg.write(prog); msg.write(bhb);
   msg.write(1); msg.write(2); // program idx
   msg.write(2); msg.write(new byte[]{0,1}); // accounts idx
   byte[] instrData=Util.concat(le32(2),le64(lam)); msg.write(instrData.length); msg.write(instrData); byte[] msgBytes=msg.toByteArray();
   Ed25519PrivateKeyParameters pr=new Ed25519PrivateKeyParameters(seed,0); Ed25519Signer signer=new Ed25519Signer(); signer.init(true,pr); signer.update(msgBytes,0,msgBytes.length); byte[] sig=signer.generateSignature();
   java.io.ByteArrayOutputStream tx=new java.io.ByteArrayOutputStream(); tx.write(1); tx.write(sig); tx.write(msgBytes); String b64=android.util.Base64.encodeToString(tx.toByteArray(),android.util.Base64.NO_WRAP);
   String res=Http.post("https://api.mainnet-beta.solana.com","{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sendTransaction\",\"params\":[\""+b64+"\",{\"encoding\":\"base64\"}]}"); JSONObject o=new JSONObject(res); if(o.has("error"))throw new Exception(o.getJSONObject("error").optString("message")); return o.optString("result"); }
}
