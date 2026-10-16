package dev.forge.runtime;
import android.app.Activity; import android.os.Bundle; import android.webkit.WebView; import java.util.zip.ZipFile; import javax.crypto.Cipher; import javax.crypto.spec.*; import java.util.Arrays;
public final class MainActivity extends Activity {
 private static byte[] read(java.io.InputStream in)throws java.io.IOException{try(java.io.InputStream input=in;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
 @Override public void onCreate(Bundle b) { super.onCreate(b); try(ZipFile z=new ZipFile(getApplicationInfo().sourceDir)) {
 byte[] p=read(z.getInputStream(z.getEntry("payload.c")));
 Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(Key.read(),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(p,0,12)));
 String html=new String(c.doFinal(Arrays.copyOfRange(p,12,p.length)),java.nio.charset.StandardCharsets.UTF_8);
 WebView w=new WebView(this); w.getSettings().setJavaScriptEnabled(true); w.getSettings().setDomStorageEnabled(true); w.getSettings().setAllowFileAccess(false); w.getSettings().setAllowContentAccess(false); w.setWebViewClient(new android.webkit.WebViewClient()); setContentView(w); w.loadDataWithBaseURL("https://app.invalid/",html,"text/html","UTF-8",null);
 } catch(Exception e) { finish(); } }
}
