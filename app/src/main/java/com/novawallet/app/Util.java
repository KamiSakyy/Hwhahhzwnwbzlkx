package com.novawallet.app;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import java.math.BigInteger; import java.nio.charset.StandardCharsets; import java.security.MessageDigest;
public final class Util{
 private Util(){}
 public static byte[] hex(String h){ if(h.startsWith("0x")||h.startsWith("0X"))h=h.substring(2); if(h.length()%2==1)h="0"+h; byte[] b=new byte[h.length()/2]; for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16); return b; }
 public static String hex(byte[] b){ StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format("%02x",x&0xff)); return s.toString(); }
 public static String hex0x(byte[] b){return "0x"+hex(b);}
 public static byte[] sha256(byte[] d){ try{return MessageDigest.getInstance("SHA-256").digest(d);}catch(Exception e){throw new RuntimeException(e);} }
 public static byte[] sha256d(byte[] d){return sha256(sha256(d));}
 public static byte[] keccak(byte[] d){ KeccakDigest k=new KeccakDigest(256); k.update(d,0,d.length); byte[] o=new byte[32]; k.doFinal(o,0); return o; }
 public static byte[] concat(byte[]... a){ int n=0; for(byte[] x:a)n+=x.length; byte[] o=new byte[n]; int p=0; for(byte[] x:a){System.arraycopy(x,0,o,p,x.length);p+=x.length;} return o; }
 public static byte[] big(BigInteger v,int len){ byte[] b=v.toByteArray(); byte[] o=new byte[len]; int src=Math.max(0,b.length-len); System.arraycopy(b,src,o,Math.max(0,len-b.length),Math.min(b.length,len)); return o; }
 public static String fmt(java.math.BigDecimal v){ if(v==null)return "0"; v=v.stripTrailingZeros(); return v.toPlainString(); }
 // Base58
 static final String B58="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
 public static String base58(byte[] d){ BigInteger n=new BigInteger(1,d); StringBuilder s=new StringBuilder(); while(n.signum()>0){BigInteger[] qr=n.divideAndRemainder(BigInteger.valueOf(58)); n=qr[0]; s.insert(0,B58.charAt(qr[1].intValue()));} for(byte b:d){if(b==0)s.insert(0,'1');else break;} return s.length()==0?"1":s.toString(); }
 public static byte[] base58decode(String s){ BigInteger n=BigInteger.ZERO; for(char c:s.toCharArray())n=n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(B58.indexOf(c))); byte[] b=n.toByteArray(); int zeros=0; while(zeros<s.length()&&s.charAt(zeros)=='1')zeros++; byte[] o=new byte[zeros+b.length-(b[0]==0?1:0)]; System.arraycopy(b,b[0]==0?1:0,o,zeros,o.length-zeros); return o; }
}
