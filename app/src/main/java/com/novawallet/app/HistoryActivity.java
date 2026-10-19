package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.graphics.Typeface; import android.net.Uri; import android.os.Bundle; import android.view.*; import android.widget.*;
import org.json.*; import java.math.*; import java.util.*; import java.util.concurrent.*;
public class HistoryActivity extends Activity{
 ExecutorService ex=Executors.newCachedThreadPool(); LinearLayout list; TextView status; String mode="evm"; int filter=0; // 0 all 1 in 2 out
 static class Row{ boolean in,ok; String addr,amt,ts,hash,kind; }
 List<Row> rows=new ArrayList<>();
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_history); NavHelper.bind(this,"history"); list=findViewById(R.id.histList); status=findViewById(R.id.tvHistStatus); mode=getIntent().getStringExtra("mode"); if(mode==null)mode="evm";
  findViewById(R.id.btnRefreshH).setOnClickListener(v->load());
  findViewById(R.id.fAll).setOnClickListener(v->{filter=0; render();});
  findViewById(R.id.fIn).setOnClickListener(v->{filter=1; render();});
  findViewById(R.id.fOut).setOnClickListener(v->{filter=2; render();});
  findViewById(R.id.btnExplorer).setOnClickListener(v->{ String u=mode.equals("btc")?"https://mempool.space/address/"+Keys.btcAddress(this):mode.equals("sol")?"https://solscan.io/account/"+Keys.solAddress(this):Networks.current(this).explorer+"/address/"+Keys.evmAddress(this); startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u))); });
  load(); }
 void load(){ status.setText("Загружаю историю…"); list.removeAllViews();
  ex.execute(()->{ try{ List<Row> out=new ArrayList<>();
    if(mode.equals("btc")){ String me=Keys.btcAddress(this); JSONArray a=new JSONArray(Http.get("https://mempool.space/api/address/"+me+"/txs"));
      for(int i=0;i<a.length();i++){ JSONObject tx=a.getJSONObject(i); Row r=new Row(); r.hash=tx.optString("txid"); r.ok=tx.optJSONObject("status")==null||tx.optJSONObject("status").optBoolean("confirmed",true); r.ts=""; r.kind="BTC";
        long inSum=0,outSum=0; JSONArray vout=tx.optJSONArray("vout"), vin=tx.optJSONArray("vin");
        if(vout!=null)for(int j=0;j<vout.length();j++){ JSONObject o=vout.getJSONObject(j); if(me.equals(o.optString("scriptpubkey_address")))inSum+=o.optLong("value"); }
        if(vin!=null)for(int j=0;j<vin.length();j++){ JSONObject pv=vin.getJSONObject(j).optJSONObject("prevout"); if(pv!=null&&me.equals(pv.optString("scriptpubkey_address")))outSum+=pv.optLong("value"); }
        r.in=outSum<=inSum; long diff=Math.abs(inSum-outSum); r.amt=new BigDecimal(diff).divide(new BigDecimal("100000000")).stripTrailingZeros().toPlainString()+" BTC"; r.addr=r.in?"отправитель в обозревателе":"получатель в обозревателе"; out.add(r); } }
    else if(mode.equals("sol")){ throw new Exception("Для Solana история открывается в обозревателе Solscan — нажмите кнопку ниже. Баланс и переводы SOL в кошельке работают."); }
    else { Networks.Net n=Networks.current(this); String base=n.chainId==11155111?"https://eth-sepolia.blockscout.com":n.chainId==1?"https://eth.blockscout.com":null; if(base==null)throw new Exception("Для сети "+n.name+" история открывается в обозревателе — нажмите кнопку ниже.");
      String me=Keys.evmAddress(this); JSONObject root=new JSONObject(Http.get(base+"/api/v2/addresses/"+me+"/transactions")); JSONArray a=root.optJSONArray("items"); if(a==null)throw new Exception("Обозреватель не вернул транзакции");
      for(int i=0;i<a.length();i++){ JSONObject tx=a.getJSONObject(i); Row r=new Row(); String from=tx.optJSONObject("from")!=null?tx.optJSONObject("from").optString("hash",""):""; String to=tx.optJSONObject("to")!=null?tx.optJSONObject("to").optString("hash",""):"";
        r.in=!from.equalsIgnoreCase(me); r.addr=r.in?from:to; r.hash=tx.optString("hash"); r.ok="ok".equals(tx.optString("status")); r.ts=tx.optString("timestamp",""); r.kind=n.symbol;
        try{ r.amt=new BigDecimal(new BigInteger(tx.optString("value","0"))).divide(new BigDecimal("1000000000000000000")).stripTrailingZeros().toPlainString()+" "+n.symbol; }catch(Exception e){ r.amt="0"; }
        String raw=tx.optString("raw_input",""); if(raw.toLowerCase().startsWith("0x"+MessengerActivity.PREFIX_HEX))r.amt+=" · сообщение";
        out.add(r); } }
    rows=out; runOnUiThread(()->{ status.setText("Адрес: "+(mode.equals("btc")?Keys.btcAddress(this):Keys.evmAddress(this))+" · операций: "+rows.size()); render(); });
   }catch(Exception e){ runOnUiThread(()->status.setText(Errors.human(e))); } }); }
 void render(){ list.removeAllViews(); boolean any=false;
  for(Row r:rows){ if(filter==1&&!r.in)continue; if(filter==2&&r.in)continue; any=true; list.addView(row(r)); }
  if(!any){ TextView t=new TextView(this); t.setText("Здесь пока пусто по выбранному фильтру."); t.setTextColor(0xFF71717A); t.setPadding(20,18,20,0); list.addView(t); } }
 View row(Row r){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(20,12,20,12);
  TextView av=new TextView(this); av.setText(r.in?"↓":"↑"); av.setTextColor(r.in?0xFF4ADE80:0xFFFAFAFA); av.setTextSize(18); av.setGravity(Gravity.CENTER); av.setLayoutParams(new LinearLayout.LayoutParams(48,48)); av.setBackgroundResource(R.drawable.bg_circle);
  LinearLayout col=new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1); lp.leftMargin=14; col.setLayoutParams(lp);
  TextView a=new TextView(this); a.setText((r.in?"Входящий · ":"Исходящий · ")+(r.addr.length()>18?r.addr.substring(0,10)+"…"+r.addr.substring(r.addr.length()-6):r.addr)); a.setTextColor(0xFFFAFAFA); a.setTextSize(14); a.setTypeface(null,Typeface.BOLD);
  TextView b=new TextView(this); b.setText((r.ok?"подтверждено":"ошибка / ожидание")+(r.ts.length()>16?" · "+r.ts.substring(0,16).replace("T"," "):"")); b.setTextColor(0xFF71717A); b.setTextSize(12);
  col.addView(a); col.addView(b); v.addView(av); v.addView(col);
  TextView c=new TextView(this); c.setText((r.in?"+":"−")+r.amt.replace("-","")); c.setTextColor(r.in?0xFF4ADE80:0xFFFAFAFA); c.setTextSize(14); c.setTypeface(null,Typeface.BOLD); v.addView(c);
  v.setClickable(true); v.setOnClickListener(x->{ if(r.hash!=null&&!r.hash.isEmpty()){ String u=mode.equals("btc")?"https://mempool.space/tx/"+r.hash:Networks.current(this).explorer+"/tx/"+r.hash; startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u))); } });
  return v; }
}
