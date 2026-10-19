package com.novawallet.app;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable;
import android.media.*; import android.net.Uri; import android.os.Bundle; import android.provider.MediaStore; import android.util.Base64;
import android.view.*; import android.widget.*; import org.json.*; import java.io.*; import java.math.*; import java.util.*; import java.util.concurrent.*; import java.nio.charset.StandardCharsets;
public class MessengerActivity extends Activity{
 ExecutorService ex=Executors.newCachedThreadPool(); LinearLayout chatsList,list; TextView status,chatStatus; ScrollView scroll;
 static final String PREFIX="NOVA1:"; static final String PREFIX_HEX=Util.hex(PREFIX.getBytes(StandardCharsets.UTF_8));
 static final int[] COLORS={0xFF5B5BD6,0xFF0E7A5F,0xFF9A5B13,0xFF7A3B69,0xFF33566E,0xFF6E3333,0xFF3E5C3A,0xFF574B90};
 String currentPeer=null; java.math.BigDecimal attachedAmt=java.math.BigDecimal.ZERO; java.math.BigDecimal feeBalance=null; java.math.BigInteger feeGasPrice=null; MediaRecorder recorder; File voiceFile; boolean recording=false; MediaPlayer player;
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
  findViewById(R.id.btnAttach).setOnClickListener(v->attachSheet());
  findViewById(R.id.btnLock).setOnClickListener(v->exchangeKeys());
  ((EditText)findViewById(R.id.etMsg)).addTextChangedListener(new android.text.TextWatcher(){ public void beforeTextChanged(CharSequence s,int a,int b2,int c){} public void onTextChanged(CharSequence s,int a,int b2,int c){ updateFeeText(); } public void afterTextChanged(android.text.Editable s){} });
  String p=getIntent().getStringExtra("peer"); if(p!=null&&valid(p)){ ((EditText)findViewById(R.id.etPeer)).setText(p); openChat(p); }
  // автообновление переписки каждые 15 секунд пока экран открыт
  refreshH=new android.os.Handler(android.os.Looper.getMainLooper()); refreshR=()->{ loadAll(); refreshH.postDelayed(refreshR,currentPeer!=null?3000:8000); }; refreshH.postDelayed(refreshR,3000); }
 android.os.Handler refreshH; Runnable refreshR;
 protected void onDestroy(){ super.onDestroy(); if(refreshH!=null&&refreshR!=null)refreshH.removeCallbacks(refreshR); if(player!=null)player.release(); }

 void attachSheet(){ String[] items={"Фото","Видео","Голосовое сообщение","Видео-кружок","Прикрепить SepoliaETH к сообщению",attachedAmt.signum()>0?"Убрать сумму":""};
   java.util.List<String> listI=new java.util.ArrayList<>(); for(String s:items)if(!s.isEmpty())listI.add(s);
   new android.app.AlertDialog.Builder(this).setItems(listI.toArray(new String[0]),(d,w)->{ String s=listI.get(w);
     if(s.equals("Фото"))pick("image/*",1); else if(s.equals("Видео"))pick("video/*",2); else if(s.equals("Голосовое сообщение"))voice();
     else if(s.equals("Видео-кружок")){ Intent i=new Intent(MediaStore.ACTION_VIDEO_CAPTURE); i.putExtra(MediaStore.EXTRA_DURATION_LIMIT,10); i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY,0); try{startActivityForResult(i,3);}catch(Exception e){err(e);} }
     else if(s.startsWith("Прикрепить"))amountDialog(); else if(s.equals("Убрать сумму")){ attachedAmt=java.math.BigDecimal.ZERO; amountChip(); updateFeeText(); }
   }).show(); }
 void amountDialog(){ EditText e=new EditText(this); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setHint("Например 0.01"); e.setText(attachedAmt.signum()>0?attachedAmt.toPlainString():""); e.setTextColor(0xFFFAFAFA);
   new android.app.AlertDialog.Builder(this).setTitle("Сумма к сообщению (SepoliaETH)").setView(e).setPositiveButton("Прикрепить",(d,w)->{ try{ attachedAmt=new java.math.BigDecimal(e.getText().toString().trim().isEmpty()?"0":e.getText().toString().trim()); }catch(Exception ex){ toast("Неверная сумма"); } amountChip(); updateFeeText(); }).setNegativeButton("Отмена",null).show(); }
 void amountChip(){ TextView c=findViewById(R.id.tvAmountChip); if(attachedAmt.signum()>0){ c.setVisibility(android.view.View.VISIBLE); c.setText("+ "+attachedAmt.stripTrailingZeros().toPlainString()+" SepoliaETH"); } else c.setVisibility(android.view.View.GONE); }
 // Панель: баланс / самая дешёвая комиссия / итого
 void updateFee(){ ex.execute(()->{ try{ Networks.Net n=Networks.ALL.get(0); feeBalance=Evm.balance(n,me()); String gp=Evm.rpcResult(n,"eth_gasPrice","[]"); feeGasPrice=new BigInteger(gp.replace("0x",""),16); runOnUiThread(this::updateFeeText); }catch(Exception ignored){} }); }
 void updateFeeText(){ TextView tv=findViewById(R.id.tvFee); if(tv==null)return;
   String draft=((EditText)findViewById(R.id.etMsg)).getText().toString(); int payloadLen=PREFIX.length()+draft.getBytes(StandardCharsets.UTF_8).length+(peerKey()!=null?140:0);
   long gas=45000L+20L*payloadLen; String feeS="…", balS="…", totS="";
   if(feeGasPrice!=null){ java.math.BigDecimal fee=new java.math.BigDecimal(feeGasPrice.multiply(java.math.BigInteger.valueOf(gas))).divide(new java.math.BigDecimal("1000000000000000000")); feeS=fee.stripTrailingZeros().toPlainString();
     if(feeBalance!=null){ balS=feeBalance.stripTrailingZeros().toPlainString(); java.math.BigDecimal total=attachedAmt.add(fee); totS=" · Итого ≈ "+total.stripTrailingZeros().toPlainString();
       if(feeBalance.compareTo(total)<0)totS+=" · НЕ ХВАТАЕТ СРЕДСТВ"; } }
   tv.setText("Баланс: "+balS+" SepoliaETH · Комиссия (самая дешёвая) ≈ "+feeS+" SepoliaETH"+totS); tv.setTextColor(feeBalance!=null&&attachedAmt.signum()>0&&feeBalance.compareTo(attachedAmt)<0?0xFFF87171:0xFF71717A); }
 // ---------- Сквозное шифрование: ECDH secp256k1 + AES-256-GCM ----------
 String peerKey(){ if(currentPeer==null)return null; return Store.pub(this).getString("pk_"+currentPeer.toLowerCase(),null); }
 void exchangeKeys(){ if(currentPeer==null)return; String myPub=Util.hex(WalletCore.pubUncompressed(Keys.evmPriv(this)));
   sendPayload(("KEY1:"+myPub).getBytes(StandardCharsets.UTF_8),BigInteger.ZERO,"Ключ шифрования"); toast("Ключ отправлен собеседнику. Когда он тоже отправит свой ключ из этого чата, сообщения станут зашифрованными."); }
 byte[] ecdh(BigInteger priv,byte[] pubBytes)throws Exception{ org.bouncycastle.math.ec.ECPoint p=WalletCore.CURVE.getCurve().decodePoint(pubBytes); org.bouncycastle.math.ec.ECPoint s=p.multiply(priv).normalize(); return Util.big(new BigInteger(1,s.getAffineXCoord().getEncoded()),32); }
 String encryptText(String plain)throws Exception{ byte[] recipientPub=Util.hex(peerKey()); BigInteger eph; do{ eph=new BigInteger(256,new java.security.SecureRandom()); }while(eph.signum()==0||eph.compareTo(WalletCore.CURVE.getN())>=0);
   byte[] shared=ecdh(eph,recipientPub); byte[] key=Util.sha256(shared); byte[] nonce=new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
   javax.crypto.Cipher c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce)); byte[] ct=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
   byte[] ephPub=WalletCore.pubUncompressed(eph); return "ENC1:"+Base64.encodeToString(Util.concat(ephPub,nonce,ct),Base64.NO_WRAP); }
 String decryptText(String payload)throws Exception{ byte[] all=Base64.decode(payload.substring(5),Base64.DEFAULT); byte[] ephPub=java.util.Arrays.copyOfRange(all,0,65); byte[] nonce=java.util.Arrays.copyOfRange(all,65,77); byte[] ct=java.util.Arrays.copyOfRange(all,77,all.length);
   byte[] shared=ecdh(Keys.evmPriv(this),ephPub); byte[] key=Util.sha256(shared); javax.crypto.Cipher c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce)); return new String(c.doFinal(ct),StandardCharsets.UTF_8); }
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
   if(payload.startsWith("KEY1:")){ m.kind="key"; m.txt=""; if(!m.out&&m.peer!=null)Store.pub(this).edit().putString("pk_"+m.peer.toLowerCase(),payload.substring(5)).apply(); return; }
   if(payload.startsWith("ENC1:")){ m.kind="enc"; try{ m.txt=decryptText(payload); }catch(Exception e){ m.txt="Зашифрованное сообщение"; } return; }
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
 String preview(Msg m){ if(m.kind.equals("key"))return "Обмен ключами шифрования"; if(m.kind.equals("enc"))return m.txt; return m.kind.equals("media")?label(m.mime):m.txt; }
 TextView avatar(String addr,int dp){ TextView t=new TextView(this); float d=getResources().getDisplayMetrics().density; int s=(int)(dp*d); t.setLayoutParams(new LinearLayout.LayoutParams(s,s)); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE); t.setTextSize(16); t.setTypeface(null,Typeface.BOLD);
  String clean=addr.replace("0x",""); t.setText(clean.isEmpty()?"?":clean.substring(0,1).toUpperCase());
  GradientDrawable g=new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(COLORS[Math.abs(addr.toLowerCase().hashCode())%COLORS.length]); t.setBackground(g); return t; }
 String shortAddr(String a){ return a.length()>16?a.substring(0,8)+"…"+a.substring(a.length()-6):a; }
 // ---------- один диалог ----------
 void updateEncState(){ TextView s=findViewById(R.id.tvEncState); if(peerKey()!=null)s.setText("Сквозное шифрование включено · Sepolia"); else s.setText("Sepolia · нажмите замок, чтобы обменяться ключами"); }
 void showList(){ currentPeer=null; findViewById(R.id.chatView).setVisibility(View.GONE); findViewById(R.id.chatsView).setVisibility(View.VISIBLE); renderChats(); }
 void openChat(String peer){ currentPeer=peer; findViewById(R.id.chatsView).setVisibility(View.GONE); findViewById(R.id.chatView).setVisibility(View.VISIBLE);
  ((TextView)findViewById(R.id.tvPeerName)).setText(shortAddr(peer)); updateFee(); updateEncState(); TextView av=findViewById(R.id.tvAvatar); View av2=avatar(peer,42); av.setText(((TextView)av2).getText()); av.setBackground(av2.getBackground()); renderChat(); }
 void renderChat(){ list.removeAllViews(); boolean any=false;
  for(Msg m:all){ if(!m.peer.equalsIgnoreCase(currentPeer))continue; if(m.kind.equals("key"))continue; any=true; bubble(m); }
  if(!any){ TextView t=new TextView(this); t.setText("Сообщений пока нет. Напишите первое — оно навсегда запишется в блокчейн Sepolia."); t.setTextColor(0xFF71717A); t.setPadding(8,16,8,0); list.addView(t); }
  scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }
 void bubble(Msg m){ LinearLayout wrap=new LinearLayout(this); wrap.setOrientation(LinearLayout.HORIZONTAL); wrap.setGravity(m.out?Gravity.END:Gravity.START); LinearLayout.LayoutParams wlp=new LinearLayout.LayoutParams(-1,-2); wlp.topMargin=8; wrap.setLayoutParams(wlp);
  LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16,12,16,12); box.setBackgroundResource(m.out?R.drawable.bubble_out:R.drawable.bubble_in); box.setLayoutParams(new LinearLayout.LayoutParams(0,-2,3));
  if(m.kind.equals("media")&&m.mime.startsWith("image")&&!m.b64.isEmpty()){ try{ byte[] bytes=Base64.decode(m.b64,Base64.DEFAULT); Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length); ImageView iv=new ImageView(this); iv.setImageBitmap(bmp); iv.setAdjustViewBounds(true); box.addView(iv); }catch(Exception ignored){} }
  TextView tv=new TextView(this); String txt=m.kind.equals("media")?label(m.mime):m.txt; tv.setText(txt); tv.setTextColor(m.out?0xFF0A0A0C:Color.WHITE); tv.setTextSize(15); box.addView(tv);
  TextView meta=new TextView(this); String mt=(m.ts!=null&&m.ts.length()>16)?m.ts.substring(11,16):""; if(m.value.compareTo(new java.math.BigDecimal("0.000001"))>=0)mt=(mt.isEmpty()?"":" · ")+"+"+m.value.setScale(6,java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()+" SepoliaETH"; meta.setText(mt); meta.setTextColor(m.out?0xFF3F3F46:0xFF71717A); meta.setTextSize(10); meta.setGravity(Gravity.END); box.addView(meta);
  if(m.kind.equals("media")&&!m.mime.startsWith("image")){ TextView play=new TextView(this); play.setText(m.mime.startsWith("audio")?"Слушать":"Смотреть"); play.setTextColor(m.out?0xFF0A0A0C:0xFFFAFAFA); play.setPadding(0,10,0,0); play.setOnClickListener(v->playMedia(m)); box.addView(play); }
  box.setOnClickListener(v->{ if(!m.hash.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://sepolia.etherscan.io/tx/"+m.hash))); });
  View sp=new View(this); sp.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1)); if(m.out){wrap.addView(sp); wrap.addView(box);} else {wrap.addView(box); wrap.addView(sp);} list.addView(wrap); }
 void playMedia(Msg m){ try{ byte[] bytes=Base64.decode(m.b64,Base64.DEFAULT); String ext=m.mime.startsWith("video")?".mp4":".m4a"; File f=new File(getCacheDir(),"nova_media"+ext); FileOutputStream o=new FileOutputStream(f); o.write(bytes); o.close();
   if(m.mime.startsWith("audio")){ if(player!=null){player.release();} player=new MediaPlayer(); player.setDataSource(f.getAbsolutePath()); player.prepare(); player.start(); toast("Воспроизведение голосового сообщения"); }
   else { Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".provider",f),"video/mp4"); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); }
  }catch(Exception e){ err(e); } }
 // ---------- отправка ----------
 BigInteger amountWei(){ return attachedAmt.multiply(new BigDecimal("1000000000000000000")).toBigIntegerExact(); }
 void sendText(){ final String msg0=((EditText)findViewById(R.id.etMsg)).getText().toString().trim(); if(currentPeer==null)return; if(msg0.isEmpty()&&amountWei().signum()==0){toast("Введите сообщение или сумму");return;} final String msg=msg0.isEmpty()?"Перевод SepoliaETH":msg0;
   if(peerKey()!=null){ ex.execute(()->{ try{ String enc=encryptText(msg); runOnUiThread(()->sendPayload(enc.getBytes(StandardCharsets.UTF_8),amountWei(),msg)); }catch(Exception e){ runOnUiThread(()->err(e)); } }); }
   else sendPayload(msg.getBytes(StandardCharsets.UTF_8),amountWei(),msg); }
 void sendPayload(byte[] payload,BigInteger wei,String optimisticText){ if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){ runOnUiThread(()->sendPayload(payload,wei,optimisticText)); return; } chatStatus.setText("Записываю в блокчейн Sepolia…"); findViewById(R.id.btnSendMsg).setEnabled(false);
  ex.execute(()->{ try{ Networks.Net n=Networks.ALL.get(0); String hash=Evm.sendData(n,Keys.evmPriv(this),currentPeer,payloadWithPrefix(payload),wei);
    Msg m=new Msg(); m.peer=currentPeer; m.out=true; m.kind=payloadText(payload).startsWith("MED1|")?"media":payloadText(payload).startsWith("KEY1:")?"key":payloadText(payload).startsWith("ENC1:")?"enc":"text"; if(m.kind.equals("enc")||m.kind.equals("key"))m.txt=optimisticText; m.txt=optimisticText; m.hash=hash; m.ts=""; try{m.value=new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"));}catch(Exception ignored){}
    all.add(m); Store.pub(this).edit().putString("pending_hash",hash).putLong("pending_chain",11155111).apply();
    runOnUiThread(()->{ ((EditText)findViewById(R.id.etMsg)).setText(""); attachedAmt=java.math.BigDecimal.ZERO; amountChip(); findViewById(R.id.btnSendMsg).setEnabled(true); chatStatus.setText("Отправлено, жду подтверждение блока… Хэш: "+hash); updateEncState(); updateFee(); renderChat(); });
    for(int i=0;i<20;i++){ Thread.sleep(4000); String st=Evm.receiptStatus(n,hash); if(st==null)continue; final boolean ok=st.startsWith("confirmed"); runOnUiThread(()->chatStatus.setText(ok?"Подтверждено в блоке. Собеседник увидит при ближайшем обновлении (у него автообновление каждые 10–15 секунд).":"Транзакция отклонена сетью. Сумма вернулась, списалась только комиссия.")); if(ok)loadAll(); break; }
   }catch(Exception e){ runOnUiThread(()->{ findViewById(R.id.btnSendMsg).setEnabled(true); err(e); }); } }); }
 String payloadText(byte[] p){ return new String(p,StandardCharsets.UTF_8); }
 byte[] payloadWithPrefix(byte[] payload){ return (PREFIX+payloadText(payload)).getBytes(StandardCharsets.UTF_8); }
 void err(Exception e){ chatStatus.setText(Errors.human(e)); status.setText(Errors.human(e)); }
 void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
 // ---------- медиа ----------
 void pick(String type,int req){ Intent i=new Intent(Intent.ACTION_GET_CONTENT); i.setType(type); i.addCategory(Intent.CATEGORY_OPENABLE); try{startActivityForResult(i,req);}catch(Exception e){err(e);} }
 void voice(){ if(recording){ try{ recorder.stop(); recorder.release(); }catch(Exception ignored){} recording=false; chatStatus.setText("Голосовое готово"); sendFile(voiceFile,"audio/mp4"); return; }
  if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},77); return; }
  try{ voiceFile=new File(getCacheDir(),"voice.m4a"); recorder=new MediaRecorder(); recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setOutputFile(voiceFile.getAbsolutePath()); recorder.prepare(); recorder.start(); recording=true; chatStatus.setText("Идёт запись… откройте скрепку и нажмите «Голосовое сообщение» ещё раз для отправки"); toast("Запись…"); }catch(Exception e){ err(e); } }
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
