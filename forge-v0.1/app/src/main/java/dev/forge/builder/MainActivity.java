package dev.forge.builder;
import android.app.Activity; import android.os.Bundle; import android.content.Intent; import android.net.Uri; import android.view.View; import android.widget.Toast; import java.io.*; import java.nio.file.Files; import java.security.*; import java.security.cert.*; import java.security.spec.PKCS8EncodedKeySpec; import java.util.*; import java.util.zip.*; import javax.crypto.*; import javax.crypto.spec.*; import com.android.apksig.ApkSigner;
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
 private byte[] source; private File result;
 @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.main); findViewById(R.id.open).setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1));findViewById(R.id.build).setOnClickListener(v->build());}
 @Override protected void onActivityResult(int r,int s,Intent d){super.onActivityResult(r,s,d);if(s!=Activity.RESULT_OK||d==null)return;try{if(r==1){try(InputStream in=getContentResolver().openInputStream(d.getData())){ source=read(in); }findViewById(R.id.build).setEnabled(source.length>0);}else if(r==2){try(OutputStream out=getContentResolver().openOutputStream(d.getData());InputStream in=new FileInputStream(result)){copy(in,out);}Toast.makeText(this,"Готово",Toast.LENGTH_SHORT).show();}}catch(Exception e){error();}}
 private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
 private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out);return out.toByteArray();}
 private void error(){Toast.makeText(this,"Не удалось выполнить",Toast.LENGTH_LONG).show();}
 private void build(){findViewById(R.id.build).setEnabled(false);findViewById(R.id.progress).setVisibility(View.VISIBLE);new Thread(()->{try{
 byte[] key={31,81,9,72,111,23,94,6,37,88,121,45,16,97,54,2,91,66,35,117,8,76,49,103,19,56,124,41,85,12,69,28}; byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] encrypted=c.doFinal(source);
 File unsigned=new File(getCacheDir(),"unsigned.apk");result=new File(getCacheDir(),"application.apk");
 try(ZipInputStream in=new ZipInputStream(getAssets().open("runtime.apk"));ZipOutputStream out=new ZipOutputStream(new FileOutputStream(unsigned))){ZipEntry e;while((e=in.getNextEntry())!=null){if(e.isDirectory()||e.getName().startsWith("META-INF/"))continue;out.putNextEntry(new ZipEntry(e.getName()));copy(in,out);out.closeEntry();}out.putNextEntry(new ZipEntry("payload.c"));out.write(iv);out.write(encrypted);out.closeEntry();}
 PrivateKey pk; X509Certificate cert;
 try(InputStream in=getAssets().open("signing.pk8")){pk=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(in)));}
 try(InputStream in=getAssets().open("signing.der")){cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}
 ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("app",pk,Collections.singletonList(cert)).build();
 new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(result).setMinSdkVersion(28).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(false).setOtherSignersSignaturesPreserved(false).build().sign();
 runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_TITLE,"application.apk"),2));
 }catch(Exception e){runOnUiThread(this::error);}finally{runOnUiThread(()->{findViewById(R.id.build).setEnabled(true);findViewById(R.id.progress).setVisibility(View.GONE);});}}).start();}
}
