package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.graphics.Color; import android.net.Uri; import android.os.Bundle; import android.view.*; import android.widget.*;
import org.json.*; import java.util.*; import java.util.concurrent.*; import java.nio.charset.StandardCharsets;
public class MessengerActivity extends Activity{
 ExecutorService ex=Executors.newCachedThreadPool(); LinearLayout list; TextView status; ScrollView scroll;
 static final String PREFIX="NOVA1:"; static final String PREFIX_HEX=Util.hex(PREFIX.getBytes(StandardCharsets.UTF_8));
 String me(){ return Keys.evmAddress(this); }
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_messenger); list=findViewById(R.id.msgList); status=findViewById(R.id.tvStatus); scroll=findViewById(R.id.scroll);
  // Messenger always on Sepolia
  Store.pub(this).edit().putInt("net",0).apply(); status.setText("Ваш адрес: "+me()+"\nВведите адрес друга и откройте переписку. Сообщения видны всем в блокчейне Sepolia.");
  findViewById(R.id.btnLoad).setOnClickListener(v->load());
  findViewById(R.id.btnRefresh).setOnClickListener(v->load());
  findViewById(R.id.btnSendMsg).setOnClickListener(v->send());
  if(getIntent().getStringExtra("peer")!=null){ ((EditText)findViewById(R.id.etPeer)).setText(getIntent().getStringExtra("peer")); load(); } }
 String peer(){ return ((EditText)findViewById(R.id.etPeer)).getText().toString().trim(); }
 void load(){ String peer=peer(); status.setText("Загружаю переписку из Sepolia…"); list.removeAllViews();
  ex.execute(()->{ try{ String url="https://eth-sepolia.blockscout.com/api/v2/addresses/"+me()+"/transactions?page_size=100"; String resp=Http.get(url); JSONObject root=new JSONObject(resp); JSONArray items=root.optJSONArray("items"); if(items==null)throw new Exception("Blockscout не вернул транзакции");
    List<JSONObject> msgs=new ArrayList<>();
    for(int i=0;i<items.length();i++){ JSONObject tx=items.getJSONObject(i); String raw=tx.optString("raw_input",""); if(raw==null||!raw.toLowerCase().startsWith("0x"+PREFIX_HEX))continue;
      String from=tx.optJSONObject("from")!=null?tx.optJSONObject("from").optString("hash",""):""; String to=tx.optJSONObject("to")!=null?tx.optJSONObject("to").optString("hash",""):"";
      if(!peer.isEmpty()&&!( (from.equalsIgnoreCase(me())&&to.equalsIgnoreCase(peer))||(from.equalsIgnoreCase(peer)&&to.equalsIgnoreCase(me())) ))continue;
      msgs.add(tx); }
    Collections.reverse(msgs);
    runOnUiThread(()->{ if(msgs.isEmpty()){ status.setText(peer.isEmpty()?"Сообщений пока нет. Отправьте первое сообщение другу.":"С этим адресом переписки пока нет. Напишите первое сообщение."); }
      else status.setText("Сообщений: "+msgs.size()+" · сеть Sepolia");
      for(JSONObject tx:msgs){ try{ String raw=tx.optString("raw_input","").substring(2+PREFIX_HEX.length()); String text=new String(Util.hex(raw),StandardCharsets.UTF_8); String from=tx.optJSONObject("from").optString("hash",""); boolean out=from.equalsIgnoreCase(me()); bubble(text,out,tx.optString("timestamp",""),tx.optString("hash","")); }catch(Exception ignored){} }
      scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); });
   }catch(Exception e){ runOnUiThread(()->status.setText("Не удалось загрузить из Blockscout Sepolia: "+e.getMessage()+"\nПроверьте интернет и нажмите обновить. Сообщения в блокчейне не потеряны.")); } }); }
 void bubble(String text,boolean out,String time,String hash){ LinearLayout wrap=new LinearLayout(this); wrap.setOrientation(LinearLayout.HORIZONTAL); wrap.setGravity(out?Gravity.END:Gravity.START); LinearLayout.LayoutParams wlp=new LinearLayout.LayoutParams(-1,-2); wlp.topMargin=8; wrap.setLayoutParams(wlp);
  TextView tv=new TextView(this); tv.setText(text+"\n"+(time.length()>16?time.substring(0,16).replace("T"," "):time)); tv.setTextColor(Color.WHITE); tv.setTextSize(15); tv.setPadding(16,12,16,12); tv.setBackgroundResource(out?R.drawable.bubble_out:R.drawable.bubble_in); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,3); tv.setLayoutParams(lp);
  tv.setOnClickListener(v->{ if(!hash.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://sepolia.etherscan.io/tx/"+hash))); });
  View spacer=new View(this); spacer.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1)); if(out){wrap.addView(spacer); wrap.addView(tv);} else {wrap.addView(tv); wrap.addView(spacer);} list.addView(wrap); }
 void send(){ String peer=peer(); String msg=((EditText)findViewById(R.id.etMsg)).getText().toString().trim();
  if(!peer.startsWith("0x")||peer.length()!=42){ toast("Введите корректный адрес собеседника 0x…"); return; } if(msg.isEmpty()){ toast("Введите сообщение"); return; }
  if(peer.equalsIgnoreCase(me())){ toast("Нельзя писать самому себе"); return; }
  status.setText("Записываю сообщение в блокчейн Sepolia…"); findViewById(R.id.btnSendMsg).setEnabled(false);
  ex.execute(()->{ try{ byte[] data=(PREFIX+msg).getBytes(StandardCharsets.UTF_8); Networks.Net n=Networks.ALL.get(0); String hash=Evm.sendData(n,Keys.evmPriv(this),peer,data);
    runOnUiThread(()->{ ((EditText)findViewById(R.id.etMsg)).setText(""); findViewById(R.id.btnSendMsg).setEnabled(true); status.setText("Сообщение отправлено в блокчейн. Хэш: "+hash+"\nОбновляю переписку через несколько секунд…"); bubble(msg,true,"",hash); scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); list.postDelayed(this::load,7000); });
   }catch(Exception e){ runOnUiThread(()->{ findViewById(R.id.btnSendMsg).setEnabled(true); status.setText("Ошибка отправки: "+e.getMessage()+"\nНужен тестовый Sepolia ETH на газ — получите в кране (экран создания токена)."); }); } }); }
 void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); } }
