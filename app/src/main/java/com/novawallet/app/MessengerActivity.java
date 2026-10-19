package com.novawallet.app;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable;
import android.media.*; import android.net.Uri; import android.os.Bundle; import android.provider.MediaStore; import android.util.Base64;
import android.view.*; import android.widget.*; import org.json.*; import java.io.*; import java.math.*; import java.util.*; import java.util.concurrent.*; import java.nio.charset.StandardCharsets;
public class MessengerActivity extends Activity{
 ExecutorService ex=Executors.newCachedThreadPool(); LinearLayout chatsList,list; TextView status,chatStatus; ScrollView scroll;
 static final String PREFIX="NOVA1:"; static final String PREFIX_HEX=Util.hex(PREFIX.getBytes(StandardCharsets.UTF_8));
 static final int[] COLORS={0xFF5B5BD6,0xFF0E7A5F,0xFF9A5B13,0xFF7A3B69,0xFF33566E,0xFF6E3333,0xFF3E5C3A,0xFF574B90};
 String currentPeer=null; MediaRecorder recorder; File voiceFile; boolean recording=false; MediaPlayer player;
 static class Msg{ String peer,txt,kind,mime,ts,hash; boolean out; BigDecimal value=BigDecimal.ZERO; String b64=""; }
 List<Msg> all=new ArrayList<>();
 String me(){ return Keys.evmAddress(this); }
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_messenger); chatsList=findViewById(R.id.chatsList); list=findViewById(R.id.msgList); status=findViewById(R.id.tvStatus); chatStatus=findViewById(R.id.tvChatStatus); scroll=findViewById(R.id.scroll);
  Store.pub(this).edit().putInt("net",0).apply();
  status.setText("Ваш адрес: "+me());
  renderFromCache(); loadAll();
  findViewById(R.id.btnRefresh).setOnClickListener(v->loadAll());
  findViewById(R.id.btnNewChat).setOnClickListener(v->{ String p=peerInput(); if(valid(p))openChat(p); else toast("Введите корректный адрес 0x из 42 символов"); });
  findViewById(R.id.btnBack).setOnClickListener(v->showList());
  findViewById(R.id.btnSendMsg).setOnClickListener(v->sendText());
  findViewById(R.id.btnPhoto).setOnClickListener(v->pick("image/*",1));
  findViewById(R.id.btnVideo).setOnClickListener(v->pick("video/*",2));
  findViewById(R.id.btnCircle).setOnClickListener(v->{ Intent i=new Intent(MediaStore.ACTION_VIDEO_CAPTURE); i.putExtra(MediaStore.EXTRA_DURATION_LIMIT,10); i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY,0); try{startActivityForResult(i,3);}catch(Exception e){err(e);} });
  findViewById(R.id.btnVoice).setOnClickListener(v->voice());
  String p=getIntent().getStringExtra("peer"); if(p!=null&&valid(p)){ ((EditText)findViewById(R.id.etPeer)).setText(p); openChat(p); }
  // автообновление переписки каждые 15 секунд пока экран открыт
  refreshH=new android.os.Handler(android.os.Looper.getMainLooper()); refreshR=()->{ loadAll(); refreshH.postDelayed(refreshR,15000); }; refreshH.postDelayed(refreshR,15000); }
 android.os.Handler refreshH; Runnable refreshR;
 protected void onDestroy(){ super.onDestroy(); if(refreshH!=null&&refreshR!=null)refreshH.removeCallbacks(refreshR); if(player!=null)player.release(); }
 String peerInput(){ return ((EditText)findViewById(R.id.etPeer)).getText().toString().trim(); }
 boolean valid(String p){ return p!=null&&p.startsWith("0x")&&p.length()==42; }
 // ---------- загрузка: 1) Blockscout 2) прямое сканирование блоков RPC (без сервера) ----------
 volatile boolean syncing=false;
 void loadAll(){ if(syncing)return; syncing=true; ex.execute(()->{ java.util.Set<String> seen=new java.util.HashSet<>(); List<Msg> merged=new ArrayList<>();
   // Локальные сообщения НИКОГДА не стираем: сначала кладём уже известные/отправленные
   for(Msg m:all){ if(m.hash!=null&&seen.add(m.hash))merged.add(m); }
   // источник 1: Blockscout БЕЗ page_size (API отклоняет этот параметр!), с пагинацией next_page_params
   try{ String nextQuery="";
     for(int page=0;page<3;page++){ String resp=Http.get("https://eth-sepolia.blockscout.com/api/v2/addresses/"+me()+"/transactions"+nextQuery); JSONObject root=new JSONObject(resp); JSONArray items=root.optJSONArray("items"); if(items==null)break;
       for(int i=0;i<items.length();i++){ JSONObject tx=items.getJSONObject(i); String raw=tx.optString("raw_input",""); if(raw.isEmpty())raw=tx.optString("input","");
         if(raw.isEmpty()&&i<30){ try{ JSONObject d=new JSONObject(Http.get("https://eth-sepolia.blockscout.com/api/v2/transactions/"+tx.optString("hash"))); String dr=d.optString("raw_input",""); if(!dr.isEmpty())tx.put("raw_input",dr); }catch(Exception ignored){} }
         Msg m=parse(tx); if(m!=null&&seen.add(m.hash)){ merged.add(m); } }
       JSONObject np=root.optJSONObject("next_page_params"); if(np==null)break; StringBuilder q=new StringBuilder("?"); java.util.Iterator<String> keys=np.keys(); boolean first=true; while(keys.hasNext()){ String k=keys.next(); if(!first)q.append("&"); first=false; q.append(k).append("=").append(np.optString(k)); } nextQuery=q.toString(); }
   }catch(Exception ignored){}
   // источник 2: прямое сканирование блоков через публичные RPC (не зависит от индексаторов)
   try{ scanBlocks(merged,seen); }catch(Exception ignored){}
   Collections.sort(merged,(a,b)->a.ts.compareTo(b.ts)); all=merged; saveCache();
   syncing=false;
   runOnUiThread(()->{ renderChats(); if(currentPeer!=null)renderChat(); status.setText("Обновлено · ваш адрес: "+me()); });
  }); }
 void saveCache(){ try{ JSONArray cache=new JSONArray(); for(Msg m:all){ JSONObject c=new JSONObject(); c.put("p",m.peer); c.put("o",m.out); c.put("x",m.kind.equals("media")?"":m.txt); c.put("k",m.kind); c.put("mi",m.mime==null?"":m.mime); c.put("ts",m.ts); c.put("h",m.hash); c.put("v",m.value.toPlainString()); cache.put(c); } Store.pub(this).edit().putString("chats_cache",cache.toString()).apply(); }catch(Exception ignored){} }
 // Пакетное сканирование блоков: eth_getBlockByNumber батчами по 40 блоков
 void scanBlocks(List<Msg> merged,java.util.Set<String> seen)throws Exception{
   Networks.Net n=Networks.ALL.get(0);
   String headHex=Evm.rpcResult(n,"eth_blockNumber","[]"); long head=Long.parseLong(headHex.replace("0x",""),16);
   long stored=Store.pub(this).getLong("msg_last_block",0); long from=stored==0?Math.max(0,head-300):stored+1; if(head-from>800)from=Math.max(0,head-800);
   if(from>head)return;
   for(long batchStart=from;batchStart<=head;batchStart+=40){
     long batchEnd=Math.min(head,batchStart+39);
     JSONArray req=new JSONArray(); for(long bnum=batchStart;bnum<=batchEnd;bnum++){ JSONObject r=new JSONObject(); r.put("jsonrpc","2.0"); r.put("id",(int)(bnum-batchStart+1)); r.put("method","eth_getBlockByNumber"); JSONArray pa=new JSONArray(); pa.put("0x"+Long.toHexString(bnum)); pa.put(true); r.put("params",pa); req.put(r); }
     String resp=Http.post(n.rpc,req.toString()); Object parsed=new JSONTokener(resp).nextValue(); if(!(parsed instanceof JSONArray))continue; JSONArray arr=(JSONArray)parsed;
     for(int i=0;i<arr.length();i++){ JSONObject r=arr.getJSONObject(i); JSONObject block=r.optJSONObject("result"); if(block==null)continue; JSONArray txs=block.optJSONArray("transactions"); if(txs==null)continue;
       for(int j=0;j<txs.length();j++){ JSONObject tx=txs.getJSONObject(j); String input=tx.optString("input",""); if(!input.toLowerCase().startsWith("0x"+PREFIX_HEX))continue;
         String fromA=tx.optString("from",""), toA=tx.optString("to",""); if(!fromA.equalsIgnoreCase(me())&&!toA.equalsIgnoreCase(me()))continue;
         String hash=tx.optString("hash",""); if(hash.isEmpty()||seen.contains(hash))continue;
         Msg m=new Msg(); m.out=fromA.equalsIgnoreCase(me()); m.peer=m.out?toA:fromA; m.hash=hash;
         try{ long ep=Long.parseLong(block.optString("timestamp","0x0").replace("0x",""),16); java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",java.util.Locale.US); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); m.ts=f.format(new java.util.Date(ep*1000)); }catch(Exception e){ m.ts=""; }
         try{ m.value=new BigDecimal(new BigInteger(tx.optString("value","0x0").replace("0x",""),16)).divide(new BigDecimal("1000000000000000000")); }catch(Exception ignored){}
         fillPayload(m,input); seen.add(hash); merged.add(m); } }
   }
   Store.pub(this).edit().putLong("msg_last_block",head).apply(); }
 void fillPayload(Msg m,String rawHex){ try{ String payload=new String(Util.hex(rawHex.substring(2+PREFIX_HEX.length())),StandardCharsets.UTF_8);
   if(payload.startsWith("MED1|")){ String[] parts=payload.split("\\|",4); m.kind="media"; m.mime=parts.length>1?parts[1]:"bin"; m.b64=parts.length>2?parts[2]:""; m.txt=label(m.mime); }
   else { m.kind="text"; m.txt=payload; } }catch(Exception ignored){} }
 void renderFromCache(){ try{ String raw=Store.pub(this).getString("chats_cache",""); if(raw.isEmpty())return; JSONArray a=new JSONArray(raw); List<Msg> msgs=new ArrayList<>();
   for(int i=0;i<a.length();i++){ JSONObject c=a.getJSONObject(i); Msg m=new Msg(); m.peer=c.optString("p"); m.out=c.optBoolean("o"); m.txt=c.optString("x"); m.ts=c.optString("ts"); m.hash=c.optString("h"); m.kind=c.optString("k","text"); m.mime=c.optString("mi",""); if(m.kind.equals("media")&&m.txt.isEmpty())m.txt=label(m.mime.isEmpty()?"bin":m.mime); try{m.value=new BigDecimal(c.optString("v","0"));}catch(Exception ignored){} msgs.add(m); }
   all=msgs; renderChats(); }catch(Exception ignored){} }
 String label(String mime){ if(mime!=null&&mime.startsWith("image"))return "Фотография"; if(mime!=null&&mime.startsWith("video"))return "Видео"; if(mime!=null&&mime.startsWith("audio"))return "Голосовое сообщение"; return "Файл"; }
 Msg parse(JSONObject tx){ try{ String raw=tx.optString("raw_input",""); if(raw.isEmpty())raw=tx.optString("input",""); if(raw==null||!raw.toLowerCase().startsWith("0x"+PREFIX_HEX))return null;
   String from=tx.optJSONObject("from")!=null?tx.optJSONObject("from").optString("hash",tx.optString("from","")):tx.optString("from",""); String to=tx.optJSONObject("to")!=null?tx.optJSONObject("to").optString("hash",""):tx.optString("to","");
   // Blockscout object form vs RPC string form
   if(tx.opt("from") instanceof String)from=tx.optString("from",""); if(tx.opt("to") instanceof String)to=tx.optString("to","");
   Msg m=new Msg(); m.out=from.equalsIgnoreCase(me()); m.peer=m.out?to:from; m.ts=tx.optString("timestamp",""); m.hash=tx.optString("hash","");
   try{ m.value=new BigDecimal(new BigInteger(tx.optString("value","0"))).divide(new BigDecimal("1000000000000000000")); }catch(Exception ignored){}
   fillPayload(m,raw); return m; }catch(Exception e){ return null; } }
 // ---------- список диалогов ----------
 void renderChats(){ chatsList.removeAllViews(); Map<String,Msg> last=new LinkedHashMap<>(); Map<String,Integer> count=new HashMap<>();
  for(Msg m:all){ last.put(m.peer.toLowerCase(),m); count.put(m.peer.toLowerCase(),count.getOrDefault(m.peer.toLowerCase(),0)+1); }
  if(last.isEmpty()){ TextView t=new TextView(this); t.setText("Диалогов пока нет.\nВведите адрес друга сверху и нажмите «Написать»."); t.setTextColor(0xFF71717A); t.setPadding(20,24,20,0); chatsList.addView(t); return; }
  List<Msg> conv=new ArrayList<>(last.values()); Collections.reverse(conv);
  for(Msg m:conv)chatsList.addView(chatRow(m,count.getOrDefault(m.peer.toLowerCase(),0))); }
 View chatRow(Msg m,int cnt){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(20,12,20,12); r.setClickable(true);
  r.addView(avatar(m.peer,46)); LinearLayout col=new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.leftMargin=14; col.setLayoutParams(lp);
  TextView a=new TextView(this); a.setText(shortAddr(m.peer)); a.setTextColor(0xFFFAFAFA); a.setTextSize(15); a.setTypeface(null,Typeface.BOLD);
  TextView b=new TextView(this); b.setText((m.out?"Вы: ":"")+preview(m)); b.setTextColor(0xFF71717A); b.setTextSize(13); b.setMaxLines(1); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
  col.addView(a); col.addView(b); r.addView(col);
  TextView c=new TextView(this); c.setText(cnt+" · >"); c.setTextColor(0xFF52525B); c.setTextSize(12); r.addView(c);
  r.setOnClickListener(v->openChat(m.peer)); return r; }
 String preview(Msg m){ return m.kind.equals("media")?label(m.mime):m.txt; }
 TextView avatar(String addr,int dp){ TextView t=new TextView(this); float d=getResources().getDisplayMetrics().density; int s=(int)(dp*d); t.setLayoutParams(new LinearLayout.LayoutParams(s,s)); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE); t.setTextSize(16); t.setTypeface(null,Typeface.BOLD);
  String clean=addr.replace("0x",""); t.setText(clean.isEmpty()?"?":clean.substring(0,1).toUpperCase());
  GradientDrawable g=new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(COLORS[Math.abs(addr.toLowerCase().hashCode())%COLORS.length]); t.setBackground(g); return t; }
 String shortAddr(String a){ return a.length()>16?a.substring(0,8)+"…"+a.substring(a.length()-6):a; }
 // ---------- один диалог ----------
 void showList(){ currentPeer=null; findViewById(R.id.chatView).setVisibility(View.GONE); findViewById(R.id.chatsView).setVisibility(View.VISIBLE); renderChats(); }
 void openChat(String peer){ currentPeer=peer; findViewById(R.id.chatsView).setVisibility(View.GONE); findViewById(R.id.chatView).setVisibility(View.VISIBLE);
  ((TextView)findViewById(R.id.tvPeerName)).setText(shortAddr(peer)); TextView av=findViewById(R.id.tvAvatar); View av2=avatar(peer,42); av.setText(((TextView)av2).getText()); av.setBackground(av2.getBackground()); renderChat(); }
 void renderChat(){ list.removeAllViews(); boolean any=false;
  for(Msg m:all){ if(!m.peer.equalsIgnoreCase(currentPeer))continue; any=true; bubble(m); }
  if(!any){ TextView t=new TextView(this); t.setText("Сообщений пока нет. Напишите первое — оно навсегда запишется в блокчейн Sepolia."); t.setTextColor(0xFF71717A); t.setPadding(8,16,8,0); list.addView(t); }
  scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }
 void bubble(Msg m){ LinearLayout wrap=new LinearLayout(this); wrap.setOrientation(LinearLayout.HORIZONTAL); wrap.setGravity(m.out?Gravity.END:Gravity.START); LinearLayout.LayoutParams wlp=new LinearLayout.LayoutParams(-1,-2); wlp.topMargin=8; wrap.setLayoutParams(wlp);
  LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16,12,16,12); box.setBackgroundResource(m.out?R.drawable.bubble_out:R.drawable.bubble_in); box.setLayoutParams(new LinearLayout.LayoutParams(0,-2,3));
  if(m.kind.equals("media")&&m.mime.startsWith("image")&&!m.b64.isEmpty()){ try{ byte[] bytes=Base64.decode(m.b64,Base64.DEFAULT); Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length); ImageView iv=new ImageView(this); iv.setImageBitmap(bmp); iv.setAdjustViewBounds(true); box.addView(iv); }catch(Exception ignored){} }
  TextView tv=new TextView(this); String txt=m.kind.equals("media")?label(m.mime):m.txt; if(m.value.signum()>0)txt+="\n+ "+m.value.stripTrailingZeros().toPlainString()+" SepoliaETH";
  txt+="\n"+(m.ts.length()>16?m.ts.substring(0,16).replace("T"," "):""); tv.setText(txt); tv.setTextColor(m.out?0xFF0A0A0C:Color.WHITE); tv.setTextSize(15); box.addView(tv);
  if(m.kind.equals("media")&&!m.mime.startsWith("image")){ TextView play=new TextView(this); play.setText(m.mime.startsWith("audio")?"Слушать":"Смотреть"); play.setTextColor(m.out?0xFF0A0A0C:0xFFFAFAFA); play.setPadding(0,10,0,0); play.setOnClickListener(v->playMedia(m)); box.addView(play); }
  box.setOnClickListener(v->{ if(!m.hash.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://sepolia.etherscan.io/tx/"+m.hash))); });
  View sp=new View(this); sp.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1)); if(m.out){wrap.addView(sp); wrap.addView(box);} else {wrap.addView(box); wrap.addView(sp);} list.addView(wrap); }
 void playMedia(Msg m){ try{ byte[] bytes=Base64.decode(m.b64,Base64.DEFAULT); String ext=m.mime.startsWith("video")?".mp4":".m4a"; File f=new File(getCacheDir(),"nova_media"+ext); FileOutputStream o=new FileOutputStream(f); o.write(bytes); o.close();
   if(m.mime.startsWith("audio")){ if(player!=null){player.release();} player=new MediaPlayer(); player.setDataSource(f.getAbsolutePath()); player.prepare(); player.start(); toast("Воспроизведение голосового сообщения"); }
   else { Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".provider",f),"video/mp4"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); }
  }catch(Exception e){ err(e); } }
 // ---------- отправка ----------
 BigInteger amountWei(){ String s=((EditText)findViewById(R.id.etAmt)).getText().toString().trim(); if(s.isEmpty())return BigInteger.ZERO; return new BigDecimal(s).multiply(new BigDecimal("1000000000000000000")).toBigIntegerExact(); }
 void sendText(){ String msg=((EditText)findViewById(R.id.etMsg)).getText().toString().trim(); if(currentPeer==null)return; if(msg.isEmpty()&&amountWei().signum()==0){toast("Введите сообщение или сумму");return;} if(msg.isEmpty())msg="Перевод SepoliaETH"; sendPayload(msg.getBytes(StandardCharsets.UTF_8),amountWei(),msg); }
 void sendPayload(byte[] payload,BigInteger wei,String optimisticText){ chatStatus.setText("Записываю в блокчейн Sepolia…"); findViewById(R.id.btnSendMsg).setEnabled(false);
  ex.execute(()->{ try{ Networks.Net n=Networks.ALL.get(0); String hash=Evm.sendData(n,Keys.evmPriv(this),currentPeer,payloadWithPrefix(payload),wei);
    Msg m=new Msg(); m.peer=currentPeer; m.out=true; m.kind=payloadText(payload).startsWith("MED1|")?"media":"text"; m.txt=optimisticText; m.hash=hash; m.ts=""; try{m.value=new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"));}catch(Exception ignored){}
    all.add(m); Store.pub(this).edit().putString("pending_hash",hash).putLong("pending_chain",11155111).apply();
    runOnUiThread(()->{ ((EditText)findViewById(R.id.etMsg)).setText(""); findViewById(R.id.btnSendMsg).setEnabled(true); chatStatus.setText("Отправлено, жду подтверждение блока… Хэш: "+hash); renderChat(); });
    for(int i=0;i<20;i++){ Thread.sleep(4000); String st=Evm.receiptStatus(n,hash); if(st==null)continue; final boolean ok=st.startsWith("confirmed"); runOnUiThread(()->chatStatus.setText(ok?"Подтверждено в блоке. Собеседник увидит при ближайшем обновлении (у него автообновление каждые 10–15 секунд).":"Транзакция отклонена сетью. Сумма вернулась, списалась только комиссия.")); if(ok)loadAll(); break; }
   }catch(Exception e){ runOnUiThread(()->{ findViewById(R.id.btnSendMsg).setEnabled(true); err(e); }); } }); }
 String payloadText(byte[] p){ return new String(p,StandardCharsets.UTF_8); }
 byte[] payloadWithPrefix(byte[] payload){ return (PREFIX+payloadText(payload)).getBytes(StandardCharsets.UTF_8); }
 void err(Exception e){ chatStatus.setText(Errors.human(e)); status.setText(Errors.human(e)); }
 void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
 // ---------- медиа ----------
 void pick(String type,int req){ Intent i=new Intent(Intent.ACTION_GET_CONTENT); i.setType(type); i.addCategory(Intent.CATEGORY_OPENABLE); try{startActivityForResult(i,req);}catch(Exception e){err(e);} }
 void voice(){ if(recording){ try{ recorder.stop(); recorder.release(); }catch(Exception ignored){} recording=false; ((android.widget.Button)findViewById(R.id.btnVoice)).setText("Голос"); sendFile(voiceFile,"audio/mp4"); return; }
  if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},77); return; }
  try{ voiceFile=new File(getCacheDir(),"voice.m4a"); recorder=new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setOutputFile(voiceFile.getAbsolutePath()); recorder.prepare(); recorder.start(); recording=true; ((android.widget.Button)findViewById(R.id.btnVoice)).setText("Стоп"); toast("Запись… нажмите «Стоп» для отправки"); }catch(Exception e){ err(e); } }
 protected void onActivityResult(int req,int res,Intent data){ super.onActivityResult(req,res,data); if(res!=RESULT_OK||data==null||data.getData()==null)return; Uri uri=data.getData();
  if(req==1)sendImage(uri); else sendUriFile(uri,req==3); }
 void sendImage(Uri uri){ ex.execute(()->{ try{ InputStream in=getContentResolver().openInputStream(uri); Bitmap bmp=BitmapFactory.decodeStream(in); if(bmp==null)throw new Exception("Не удалось прочитать изображение");
   int max=520; float sc=Math.min(1f,(float)max/Math.max(bmp.getWidth(),bmp.getHeight())); Bitmap small=Bitmap.createScaledBitmap(bmp,Math.max(1,(int)(bmp.getWidth()*sc)),Math.max(1,(int)(bmp.getHeight()*sc)),true);
   byte[] jpg=null; for(int q=70;q>=25;q-=15){ ByteArrayOutputStream o=new ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG,q,o); jpg=o.toByteArray(); if(jpg.length<=26000)break; } if(jpg.length>30000)throw new Exception("Фото слишком большое даже после сжатия ("+jpg.length/1024+" КБ). Выберите изображение поменьше.");
   String payload="MED1|image/jpeg|"+Base64.encodeToString(jpg,Base64.NO_WRAP); runOnUiThread(()->sendPayload(payload.getBytes(StandardCharsets.UTF_8),BigInteger.ZERO,"Фотография"));
  }catch(Exception e){ runOnUiThread(()->err(e)); } }); }
 void sendUriFile(Uri uri,boolean isVideo){ try{ File f=copyUri(uri,isVideo?"video.mp4":"file.bin"); sendFile(f,isVideo?"video/mp4":"video/mp4"); }catch(Exception e){ err(e); } }
 File copyUri(Uri uri,String name)throws Exception{ File f=new File(getCacheDir(),name); InputStream in=getContentResolver().openInputStream(uri); FileOutputStream o=new FileOutputStream(f); byte[] buf=new byte[8192]; int r; while((r=in.read(buf))>0)o.write(buf,0,r); o.close(); in.close(); return f; }
 void sendFile(File f,String mime){ try{ if(f==null||!f.exists())throw new Exception("Файл не найден"); byte[] bytes=new byte[(int)f.length()]; FileInputStream in=new FileInputStream(f); int off=0,r; while(off<bytes.length&&(r=in.read(bytes,off,bytes.length-off))>0)off+=r; in.close();
   if(bytes.length>64000)throw new Exception("Файл слишком большой для записи в блокчейн: "+bytes.length/1024+" КБ, максимум 64 КБ. Запишите короче или выберите файл меньше.");
   String payload="MED1|"+mime+"|"+Base64.encodeToString(bytes,Base64.NO_WRAP); sendPayload(payload.getBytes(StandardCharsets.UTF_8),BigInteger.ZERO,label(mime));
  }catch(Exception e){ err(e); } }
}
