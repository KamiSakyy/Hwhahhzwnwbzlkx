package com.novawallet.app;
import android.content.*; import androidx.security.crypto.*;
import java.io.IOException; import java.security.GeneralSecurityException;
public class Store{
 static SharedPreferences prefs(Context c){ try{ return EncryptedSharedPreferences.create(c,"nova_secure","_androidx_security_master_key_",EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);}catch(Exception e){ return c.getSharedPreferences("nova_plain",Context.MODE_PRIVATE);} }
 public static boolean hasWallet(Context c){return prefs(c).contains("mnemonic")||prefs(c).contains("privkey");}
 public static void saveMnemonic(Context c,String m){prefs(c).edit().putString("mnemonic",m.trim().toLowerCase()).apply();}
 public static void savePriv(Context c,String p){prefs(c).edit().putString("privkey",p).apply();}
 public static String mnemonic(Context c){return prefs(c).getString("mnemonic","");}
 public static String privkey(Context c){return prefs(c).getString("privkey","");}
 public static void setPin(Context c,String pin){prefs(c).edit().putString("pin",Util.hex(Util.sha256(pin.getBytes()))).apply();}
 public static boolean checkPin(Context c,String pin){String h=prefs(c).getString("pin",""); return h.isEmpty()||h.equals(Util.hex(Util.sha256(pin.getBytes()))); }
 public static boolean hasPin(Context c){return prefs(c).contains("pin");}
 public static void wipe(Context c){prefs(c).edit().clear().apply(); c.getSharedPreferences("nova_public",Context.MODE_PRIVATE).edit().clear().apply();}
 public static SharedPreferences pub(Context c){return c.getSharedPreferences("nova_public",Context.MODE_PRIVATE);}
}
