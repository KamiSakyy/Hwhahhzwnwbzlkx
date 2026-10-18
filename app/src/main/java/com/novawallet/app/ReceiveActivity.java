package com.novawallet.app;
import android.app.Activity; import android.content.*; import android.graphics.Bitmap; import android.os.Bundle; import android.widget.*;
import com.google.zxing.*; import com.google.zxing.common.BitMatrix;
public class ReceiveActivity extends Activity{ String addr; protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_receive); String mode=getIntent().getStringExtra("mode"); if(mode==null)mode="evm";
  if(mode.equals("btc")){addr=Keys.btcAddress(this); ((TextView)findViewById(R.id.tvNet)).setText("Bitcoin (BTC) • SegWit");} else if(mode.equals("sol")){addr=Keys.solAddress(this); ((TextView)findViewById(R.id.tvNet)).setText("Solana (SOL)");}
  else { addr=Keys.evmAddress(this); ((TextView)findViewById(R.id.tvNet)).setText(Networks.current(this).name+" • "+Networks.current(this).symbol); }
  ((TextView)findViewById(R.id.tvAddr)).setText(addr); try{ ((ImageView)findViewById(R.id.ivQr)).setImageBitmap(qr(addr)); }catch(Exception ignored){}
  findViewById(R.id.btnCopy).setOnClickListener(v->{copy(addr); Toast.makeText(this,"Адрес скопирован",Toast.LENGTH_SHORT).show();});
  findViewById(R.id.btnShare).setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT,"Мой адрес "+Networks.current(this).name+": "+addr); startActivity(Intent.createChooser(i,"Поделиться")); }); }
 void copy(String s){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("addr",s));}
 Bitmap qr(String text)throws Exception{ BitMatrix m=new MultiFormatWriter().encode(text,BarcodeFormat.QR_CODE,520,520); Bitmap bmp=Bitmap.createBitmap(520,520,Bitmap.Config.RGB_565); for(int x=0;x<520;x++)for(int y=0;y<520;y++)bmp.setPixel(x,y,m.get(x,y)?0xFF000000:0xFFFFFFFF); return bmp; } }
