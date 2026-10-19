package com.novawallet.app;
import android.app.*; import android.content.*; import android.graphics.Color; import android.net.Uri; import android.os.Bundle; import android.view.View; import android.widget.*;
import java.math.*; import java.util.*; import java.util.concurrent.*;
import com.google.zxing.integration.android.*;
public class HomeActivity extends Activity{
 ExecutorService ex=Executors.newCachedThreadPool(); String mode="evm"; TextView tvBal,tvFiat,tvAddr,tvNet; LinearLayout list; Spinner sp;
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_home); tvBal=findViewById(R.id.tvBalance); tvFiat=findViewById(R.id.tvFiat); tvAddr=findViewById(R.id.tvAddress); tvNet=findViewById(R.id.tvNetName); list=findViewById(R.id.tokenList); sp=findViewById(R.id.spNet);
  List<String> names=new ArrayList<>(); for(Networks.Net n:Networks.ALL)names.add((n.test?" ":"")+n.name); ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names); sp.setAdapter(ad); sp.setSelection(Store.pub(this).getInt("net",0));
  sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ public void onItemSelected(AdapterView<?> p,View v,int i,long id){ Store.pub(HomeActivity.this).edit().putInt("net",i).apply(); if(mode.equals("evm"))updateChips(); refresh(); } public void onNothingSelected(AdapterView<?> p){} });
  chip(R.id.chipEvm,"evm"); chip(R.id.chipBtc,"btc"); chip(R.id.chipSol,"sol");
  tvAddr.setOnClickListener(v->{ copy(addr()); toast("Адрес скопирован"); });
  click(R.id.btnSend,()->{ Intent i=new Intent(this,SendActivity.class); i.putExtra("mode",mode); startActivity(i); });
  click(R.id.btnReceive,()->{ Intent i=new Intent(this,ReceiveActivity.class); i.putExtra("mode",mode); startActivity(i); });
  click(R.id.btnScan,()->{ IntentIntegrator ii=new IntentIntegrator(this); ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE); ii.setPrompt("Наведите на QR адрес"); ii.initiateScan(); });
  click(R.id.btnSwap,()->{ Intent i=new Intent(this,BrowserActivity.class); i.putExtra("url","https://app.uniswap.org/swap"); startActivity(i); });
  click(R.id.btnCreateToken,()->startActivity(new Intent(this,CreateTokenActivity.class)));
  click(R.id.btnChat,()->startActivity(new Intent(this,MessengerActivity.class)));
  click(R.id.btnBrowser,()->startActivity(new Intent(this,BrowserActivity.class)));
  click(R.id.btnNft,()->{ Intent i=new Intent(this,ToolsActivity.class); i.putExtra("mode","nft"); startActivity(i); });
  click(R.id.btnMore,()->{ Intent i=new Intent(this,ToolsActivity.class); i.putExtra("mode","menu"); startActivity(i); });
  click(R.id.btnAddToken,()->startActivity(new Intent(this,AddTokenActivity.class)));
  click(R.id.btnHistory,()->{ String u; if(mode.equals("btc"))u="https://mempool.space/address/"+addr(); else if(mode.equals("sol"))u="https://solscan.io/account/"+addr(); else u=Networks.current(this).explorer+"/address/"+addr(); startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u))); });
  refresh(); }
 String addr(){ return mode.equals("btc")?Keys.btcAddress(this):mode.equals("sol")?Keys.solAddress(this):Keys.evmAddress(this); }
 void chip(int id,String m){ findViewById(id).setOnClickListener(v->{mode=m; updateChips(); refresh();}); }
 void updateChips(){ setChip(R.id.chipEvm,mode.equals("evm")); setChip(R.id.chipBtc,mode.equals("btc")); setChip(R.id.chipSol,mode.equals("sol")); }
 void setChip(int id,boolean on){ com.google.android.material.button.MaterialButton b=findViewById(id); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(on?0xFFF4F4F5:0x00000000)); b.setTextColor(on?0xFF0A0A0C:0xFF8E8E99); b.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF2A2A44)); }
 void click(int id,Runnable r){ findViewById(id).setOnClickListener(v->r.run()); }
 void copy(String s){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("a",s));}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
 android.os.Handler autoH=new android.os.Handler(android.os.Looper.getMainLooper()); Runnable autoR;
 protected void onResume(){ super.onResume(); if(tvBal!=null)refresh(); autoR=()->{ refresh(); autoH.postDelayed(autoR,10000); }; autoH.postDelayed(autoR,10000); }
 protected void onPause(){ super.onPause(); if(autoR!=null)autoH.removeCallbacks(autoR); }
 protected void onActivityResult(int req,int res,Intent data){ IntentResult r=IntentIntegrator.parseActivityResult(req,res,data); if(r!=null&&r.getContents()!=null){ String a=r.getContents().replace("ethereum:","").replace("bitcoin:","").replace("solana:","").split("[?@]")[0]; Intent i=new Intent(this,SendActivity.class); i.putExtra("mode",mode); i.putExtra("to",a); startActivity(i); } else super.onActivityResult(req,res,data); }
 void refresh(){ tvAddr.setText(shortAddr(addr())); list.removeAllViews();
  if(mode.equals("btc")){ tvNet.setText("Bitcoin · Mainnet"); row("Bitcoin","BTC","…",null); ex.execute(()->{ BigDecimal bal=BtcSol.btcBalance(addr()); double pr=Evm.priceUsd("bitcoin"); runOnUiThread(()->{ tvBal.setText(Util.fmt(bal)+" BTC"); tvFiat.setText("$"+String.format(Locale.US,"%.2f",bal.doubleValue()*pr)); replaceRow(0,"Bitcoin","BTC",Util.fmt(bal),null); }); }); return; }
  if(mode.equals("sol")){ tvNet.setText("Solana · Mainnet"); row("◎ Solana","SOL","…",null); ex.execute(()->{ BigDecimal bal=BtcSol.solBalance(addr()); double pr=Evm.priceUsd("solana"); runOnUiThread(()->{ tvBal.setText(Util.fmt(bal)+" SOL"); tvFiat.setText("$"+String.format(Locale.US,"%.2f",bal.doubleValue()*pr)); replaceRow(0,"◎ Solana","SOL",Util.fmt(bal),null); }); }); return; }
  Networks.Net n=Networks.current(this); String pend=Store.pub(this).getString("pending_hash","");
   if(!pend.isEmpty()&&Store.pub(this).getLong("pending_chain",0)==n.chainId){ tvNet.setText(n.name+" · перевод ожидает подтверждения…"); ex.execute(()->{ String st=Evm.receiptStatus(n,pend); if(st!=null&&st.startsWith("confirmed")){ Store.pub(this).edit().remove("pending_hash").apply(); runOnUiThread(()->{ toast("Перевод подтверждён"); refresh(); }); } }); }
   else tvNet.setText(n.name+" · ChainID "+n.chainId); row(""+n.name,n.symbol,"…",null);
  List<Networks.Token> toks=new ArrayList<>(); toks.addAll(Networks.known(n.chainId)); toks.addAll(Networks.tokens(this,n.chainId));
  for(Networks.Token t:toks) row(" "+t.name,t.symbol,"…",t);
  ex.execute(()->{ try{ BigDecimal bal=Evm.balance(n,addr()); String cg=n.symbol.equals("ETH")?"ethereum":n.symbol.equals("BNB")?"binancecoin":n.symbol.equals("POL")?"matic-network":n.symbol.equals("AVAX")?"avalanche-2":"ethereum"; double pr=n.test?0:Evm.priceUsd(cg); runOnUiThread(()->{ tvBal.setText(Util.fmt(bal)+" "+n.symbol); tvFiat.setText(n.test?"Тестовая сеть — монеты без цены":"$"+String.format(Locale.US,"%.2f",bal.doubleValue()*pr)); replaceRow(0,""+n.name,n.symbol,Util.fmt(bal),null); }); }catch(Exception e){ runOnUiThread(()->{ tvBal.setText("Нет соединения"); tvFiat.setText(" "+e.getMessage()+" — нажмите на баланс, чтобы повторить"); tvBal.setOnClickListener(v->refresh()); }); }
   for(int i=0;i<toks.size();i++){ Networks.Token t=toks.get(i); int idx=i+1; try{ BigDecimal bal=Evm.erc20Balance(n,addr(),t.address,t.decimals); runOnUiThread(()->replaceRow(idx," "+t.name,t.symbol,Util.fmt(bal),t)); }catch(Exception ignored){} } }); }
 String shortAddr(String a){ return a.length()>18?a.substring(0,10)+"…"+a.substring(a.length()-6):a; }
 void row(String name,String sym,String bal,Networks.Token t){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(16,14,16,14); r.setBackgroundResource(R.drawable.bg_chip); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.topMargin=8; r.setLayoutParams(lp);
  TextView a=new TextView(this); a.setText(name+"\n"+sym); a.setTextColor(Color.WHITE); a.setTextSize(15); a.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1)); TextView b2=new TextView(this); b2.setText(bal); b2.setTextColor(0xFFFFFFFF); b2.setTextSize(16); b2.setTypeface(null,android.graphics.Typeface.BOLD); r.addView(a); r.addView(b2); r.setTag(b2);
  if(t!=null)r.setOnClickListener(v->{ Intent i=new Intent(this,SendActivity.class); i.putExtra("mode","evm"); i.putExtra("token",t.address); i.putExtra("symbol",t.symbol); i.putExtra("decimals",t.decimals); startActivity(i); }); list.addView(r); }
 void replaceRow(int idx,String name,String sym,String bal,Networks.Token t){ if(idx<list.getChildCount()){ LinearLayout r=(LinearLayout)list.getChildAt(idx); ((TextView)r.getChildAt(0)).setText(name+"\n"+sym); ((TextView)r.getChildAt(1)).setText(bal); } }
}
