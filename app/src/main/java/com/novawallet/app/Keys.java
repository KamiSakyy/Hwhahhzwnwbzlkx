package com.novawallet.app;
import android.content.Context; import java.math.BigInteger;
public class Keys{
 public static BigInteger evmPriv(Context c){ String m=Store.mnemonic(c); if(!m.isEmpty())return WalletCore.evmPriv(m); String p=Store.privkey(c).replace("0x",""); return new BigInteger(p,16); }
 public static String evmAddress(Context c){ try{return WalletCore.evmAddress(evmPriv(c));}catch(Exception e){return "0x0000000000000000000000000000000000000000";} }
 public static BigInteger btcPriv(Context c){ String m=Store.mnemonic(c); if(!m.isEmpty())return WalletCore.btcPriv(m); return new BigInteger(1,Util.sha256(Util.hex(Store.privkey(c).replace("0x","")))); }
 public static String btcAddress(Context c){return WalletCore.btcAddress(btcPriv(c));}
 public static byte[] solSeed(Context c){ String m=Store.mnemonic(c); if(!m.isEmpty())return WalletCore.solPriv(m); return Util.sha256(Util.hex(Store.privkey(c).replace("0x",""))); }
 public static String solAddress(Context c){return WalletCore.solAddress(solSeed(c));}
}
