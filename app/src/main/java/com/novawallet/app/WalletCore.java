package com.novawallet.app;
import org.bouncycastle.asn1.sec.SECNamedCurves; import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA512Digest; import org.bouncycastle.crypto.macs.HMac; import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters; import org.bouncycastle.crypto.params.ECPrivateKeyParameters; import org.bouncycastle.crypto.signers.ECDSASigner; import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECPoint; import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import java.math.BigInteger; import java.nio.charset.StandardCharsets; import java.security.SecureRandom; import java.util.*;
public final class WalletCore{
 static final X9ECParameters CURVE=SECNamedCurves.getByName("secp256k1");
 static final ECDomainParameters DOM=new ECDomainParameters(CURVE.getCurve(),CURVE.getG(),CURVE.getN(),CURVE.getH());
 public static String newMnemonic(){ byte[] e=new byte[16]; new SecureRandom().nextBytes(e); return toMnemonic(e); }
 public static String toMnemonic(byte[] entropy){ byte[] h=Util.sha256(entropy); int cs=entropy.length*8/32; StringBuilder bits=new StringBuilder(); for(byte b:entropy)bits.append(String.format("%8s",Integer.toBinaryString(b&0xff)).replace(' ','0')); bits.append(String.format("%8s",Integer.toBinaryString(h[0]&0xff)).replace(' ','0').substring(0,cs)); StringBuilder out=new StringBuilder(); for(int i=0;i<bits.length();i+=11){ if(i>0)out.append(' '); out.append(Wordlist.WORDS[Integer.parseInt(bits.substring(i,i+11),2)]); } return out.toString(); }
 public static boolean validMnemonic(String m){ String[] w=m.trim().toLowerCase().split("\\s+"); if(!(w.length==12||w.length==15||w.length==18||w.length==21||w.length==24))return false; List<String> list=Arrays.asList(Wordlist.WORDS); for(String x:w)if(!list.contains(x))return false; return true; }
 static byte[] hmac512(byte[] key,byte[] data){ HMac h=new HMac(new SHA512Digest()); h.init(new KeyParameter(key)); h.update(data,0,data.length); byte[] o=new byte[64]; h.doFinal(o,0); return o; }
 public static byte[] seed(String mnemonic,String pass){ // PBKDF2-HMAC-SHA512 2048
   byte[] pw=mnemonic.getBytes(StandardCharsets.UTF_8); byte[] salt=("mnemonic"+(pass==null?"":pass)).getBytes(StandardCharsets.UTF_8);
   byte[] u=hmac512(pw,Util.concat(salt,new byte[]{0,0,0,1})); byte[] t=u.clone();
   for(int i=1;i<2048;i++){ u=hmac512(pw,u); for(int j=0;j<64;j++)t[j]^=u[j]; } return t; }
 static class Node{ BigInteger priv; byte[] chain; Node(BigInteger p,byte[] c){priv=p;chain=c;} }
 static Node master(byte[] seed){ byte[] I=hmac512("Bitcoin seed".getBytes(StandardCharsets.UTF_8),seed); return new Node(new BigInteger(1,Arrays.copyOfRange(I,0,32)),Arrays.copyOfRange(I,32,64)); }
 static Node child(Node n,long idx,byte[] seedForEd){
   byte[] data; boolean hard=idx>=0x80000000L;
   if(hard) data=Util.concat(new byte[]{0},Util.big(n.priv,32),ser32(idx)); else data=Util.concat(pubCompressed(n.priv),ser32(idx));
   byte[] I=hmac512(n.chain,data); BigInteger p=new BigInteger(1,Arrays.copyOfRange(I,0,32)).add(n.priv).mod(CURVE.getN()); return new Node(p,Arrays.copyOfRange(I,32,64)); }
 static byte[] ser32(long v){ return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v}; }
 public static byte[] pubCompressed(BigInteger priv){ return CURVE.getG().multiply(priv).normalize().getEncoded(true); }
 public static byte[] pubUncompressed(BigInteger priv){ return CURVE.getG().multiply(priv).normalize().getEncoded(false); }
 static Node derive(byte[] seed,int... path){ Node n=master(seed); for(int p:path)n=child(n,p&0xffffffffL,seed); return n; }
 static final int H=0x80000000;
 public static BigInteger evmPriv(String mnemonic){ return derive(seed(mnemonic,""),new int[]{44|H,60|H,0|H,0,0}).priv; }
 public static BigInteger btcPriv(String mnemonic){ return derive(seed(mnemonic,""),new int[]{84|H,0|H,0|H,0,0}).priv; }
 public static String evmAddress(BigInteger priv){ byte[] pub=pubUncompressed(priv); byte[] xy=Arrays.copyOfRange(pub,1,65); byte[] h=Util.keccak(xy); byte[] a=Arrays.copyOfRange(h,12,32); String low=Util.hex(a); String hash=Util.hex(Util.keccak(low.getBytes(StandardCharsets.US_ASCII))); StringBuilder out=new StringBuilder("0x"); for(int i=0;i<40;i++){char c=low.charAt(i); out.append(Character.digit(hash.charAt(i),16)>=8?Character.toUpperCase(c):c);} return out.toString(); }
 public static String btcAddress(BigInteger priv){ byte[] pub=pubCompressed(priv); byte[] h160=ripemd160(Util.sha256(pub)); return bech32("bc",0,h160); }
 static byte[] ripemd160(byte[] d){ org.bouncycastle.crypto.digests.RIPEMD160Digest r=new org.bouncycastle.crypto.digests.RIPEMD160Digest(); r.update(d,0,d.length); byte[] o=new byte[20]; r.doFinal(o,0); return o; }
 static String bech32(String hrp,int ver,byte[] prog){ // BIP173
   int[] vals=new int[prog.length]; for(int i=0;i<prog.length;i++)vals[i]=prog[i]&0xff; int[] conv=convertBits(vals,8,5,true); int[] data=new int[conv.length+1]; data[0]=ver; System.arraycopy(conv,0,data,1,conv.length);
   String cs="qpzry9x8gf2tvdw0s3jn54khce6mua7l"; int polymod=1; int[] hrpExp=new int[hrp.length()*2+1]; for(int i=0;i<hrp.length();i++){hrpExp[i]=hrp.charAt(i)>>5; hrpExp[hrp.length()+1+i]=hrp.charAt(i)&31;}
   int[] all=Util2.concatInt(hrpExp,data,new int[6]); polymod=polymod(all)^1;
   StringBuilder sb=new StringBuilder(hrp+"1"); for(int v:data)sb.append(cs.charAt(v)); for(int i=0;i<6;i++)sb.append(cs.charAt((polymod>>>(5*(5-i)))&31)); return sb.toString(); }
 static int polymod(int[] v){ int chk=1; int[] gen={0x3b6a57b2,0x26508e6d,0x1ea119fa,0x3d4233dd,0x2a1462b3}; for(int x:v){int top=chk>>>25; chk=((chk&0x1ffffff)<<5)^x; for(int i=0;i<5;i++)if(((top>>>i)&1)==1)chk^=gen[i];} return chk; }
 static int[] convertBits(int[] data,int from,int to,boolean pad){ int acc=0,bits=0; List<Integer> out=new ArrayList<>(); int maxv=(1<<to)-1; for(int v:data){acc=(acc<<from)|v; bits+=from; while(bits>=to){bits-=to; out.add((acc>>>bits)&maxv);}} if(pad&&bits>0)out.add((acc<<(to-bits))&maxv); int[] r=new int[out.size()]; for(int i=0;i<r.length;i++)r[i]=out.get(i); return r; }
 // Solana SLIP-0010 ed25519 hardened only
 public static byte[] solSeed(String mnemonic){ return seed(mnemonic,""); }
 static byte[][] slipChild(byte[] k,byte[] c,long idx){ byte[] data=Util.concat(new byte[]{0},k,ser32(idx)); byte[] I=hmac512(c,data); return new byte[][]{Arrays.copyOfRange(I,0,32),Arrays.copyOfRange(I,32,64)}; }
 public static byte[] solPriv(String mnemonic){ byte[] I=hmac512("ed25519 seed".getBytes(StandardCharsets.UTF_8),seed(mnemonic,"")); byte[] k=Arrays.copyOfRange(I,0,32),c=Arrays.copyOfRange(I,32,64); int[] path={44|H,501|H,0|H,0|H}; for(int p:path){ byte[][] r=slipChild(k,c,p&0xffffffffL); k=r[0];c=r[1]; } return k; }
 public static String solAddress(byte[] solPrivSeed){ Ed25519PrivateKeyParameters pr=new Ed25519PrivateKeyParameters(solPrivSeed,0); return Util.base58(pr.generatePublicKey().getEncoded()); }
 public static BigInteger[] signSecp(BigInteger priv,byte[] hash){ ECDSASigner s=new ECDSASigner(new HMacDSAKCalculator(new org.bouncycastle.crypto.digests.SHA256Digest())); s.init(true,new ECPrivateKeyParameters(priv,DOM)); BigInteger[] sig=s.generateSignature(hash); BigInteger half=CURVE.getN().shiftRight(1); if(sig[1].compareTo(half)>0)sig[1]=CURVE.getN().subtract(sig[1]); return sig; }
}
class Util2{ static int[] concatInt(int[]... a){int n=0;for(int[] x:a)n+=x.length;int[] o=new int[n];int p=0;for(int[] x:a){System.arraycopy(x,0,o,p,x.length);p+=x.length;}return o;} }
