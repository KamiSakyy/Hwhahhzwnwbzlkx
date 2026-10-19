package com.novawallet.app;
import android.app.Activity; import android.os.Bundle; import android.view.View; import android.widget.*; import java.math.*; import java.util.concurrent.*;
public class SendActivity extends Activity{ ExecutorService ex=Executors.newSingleThreadExecutor(); String mode,token,symbol; int decimals=18; String lastBal="";
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_send); mode=getIntent().getStringExtra("mode"); if(mode==null)mode="evm"; token=getIntent().getStringExtra("token"); symbol=getIntent().getStringExtra("symbol"); decimals=getIntent().getIntExtra("decimals",18);
  if(getIntent().getStringExtra("to")!=null)((EditText)findViewById(R.id.etTo)).setText(getIntent().getStringExtra("to"));
  String title=mode.equals("btc")?"Отправить BTC":mode.equals("sol")?"Отправить SOL":token!=null?"Отправить "+symbol:"Отправить "+Networks.current(this).symbol; ((TextView)findViewById(R.id.tvTitle)).setText(title);
  findViewById(R.id.btnSend).setOnClickListener(v->send());
  findViewById(R.id.btnMax).setOnClickListener(v->{ if(!lastBal.isEmpty()){ ((EditText)findViewById(R.id.etAmt)).setText(lastBal); if(token==null)toast("Оставьте немного на комиссию сети"); } });
  loadBal();
  findViewById(R.id.btnCopy).setOnClickListener(v->{ String s=((TextView)findViewById(R.id.tvResult)).getText().toString(); ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("tx",s)); toast("Скопировано"); }); }
 void loadBal(){ ((TextView)findViewById(R.id.tvBal)).setText("Загружаю баланс…"); ex.execute(()->{ try{ String sym; java.math.BigDecimal bal; String addr;
   if(mode.equals("btc")){ addr=Keys.btcAddress(this); bal=BtcSol.btcBalance(addr); sym="BTC"; }
   else if(mode.equals("sol")){ addr=Keys.solAddress(this); bal=BtcSol.solBalance(addr); sym="SOL"; }
   else if(token!=null){ bal=Evm.erc20Balance(Networks.current(this),Keys.evmAddress(this),token,decimals); sym=symbol; }
   else { bal=Evm.balance(Networks.current(this),Keys.evmAddress(this)); sym=Networks.current(this).symbol; }
   String bs=bal.stripTrailingZeros().toPlainString(); runOnUiThread(()->{ lastBal=bs; ((TextView)findViewById(R.id.tvBal)).setText("Доступно: "+bs+" "+sym); });
  }catch(Exception e){ runOnUiThread(()->((TextView)findViewById(R.id.tvBal)).setText("Баланс не загрузился — проверьте интернет")); } }); }
 void send(){ String to=((EditText)findViewById(R.id.etTo)).getText().toString().trim(); String amtS=((EditText)findViewById(R.id.etAmt)).getText().toString().trim(); if(to.isEmpty()||amtS.isEmpty()){toast("Заполните адрес и сумму");return;}
  findViewById(R.id.prog).setVisibility(View.VISIBLE); ((Button)findViewById(R.id.btnSend)).setEnabled(false);
  ex.execute(()->{ try{ BigDecimal amt=new BigDecimal(amtS); String hash;
    if(mode.equals("btc"))hash=BtcSol.btcSend(Keys.btcPriv(this),Keys.btcAddress(this),to,amt);
    else if(mode.equals("sol"))hash=BtcSol.solSend(Keys.solSeed(this),Keys.solAddress(this),to,amt);
    else if(token!=null)hash=Evm.sendErc20(Networks.current(this),Keys.evmPriv(this),token,to,amt,decimals);
    else hash=Evm.sendEth(Networks.current(this),Keys.evmPriv(this),to,amt);
    String link=mode.equals("btc")?"https://mempool.space/tx/"+hash:mode.equals("sol")?"https://solscan.io/tx/"+hash:Networks.current(this).explorer+"/tx/"+hash;
    if(mode.equals("evm")){ Store.pub(this).edit().putString("pending_hash",hash).putLong("pending_chain",Networks.current(this).chainId).putString("pending_to",to).apply(); }
    runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText("Отправлено в сеть, ожидаю подтверждение…\nХэш: "+hash+"\n"+link+"\n\nДеньги списаны с вашего адреса сразу после подтверждения блока (обычно 12–30 секунд в Sepolia). У получателя баланс обновится автоматически."); findViewById(R.id.btnCopy).setVisibility(View.VISIBLE); done(); watchReceipt(hash); });
   }catch(Exception e){ runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText(Errors.human(e)); findViewById(R.id.btnCopy).setVisibility(View.VISIBLE); done(); }); } }); }
 void watchReceipt(String hash){ if(!mode.equals("evm"))return; Networks.Net n=Networks.current(this); new Thread(()->{ for(int i=0;i<30;i++){ try{Thread.sleep(4000);}catch(Exception ignored){} String st=Evm.receiptStatus(n,hash); if(st==null)continue; if(st.startsWith("confirmed")){ Store.pub(this).edit().remove("pending_hash").apply(); runOnUiThread(()->{ TextView tv=findViewById(R.id.tvResult); tv.setText(tv.getText()+"\n\nПодтверждено в блоке "+st.split("\\|")[1]+". Перевод завершён."); }); } else if(st.startsWith("failed")){ String reason=Evm.txFailureReason(n,hash); runOnUiThread(()->{ TextView tv=findViewById(R.id.tvResult); tv.setText(tv.getText()+"\n\nТранзакция не выполнена. "+(reason!=null?reason:"Сумма не списалась — перевод откачен сетью, сгорела только комиссия за газ. Проверьте детали по хэшу в обозревателе.")); findViewById(R.id.btnCopy).setVisibility(View.VISIBLE); }); } break; } }).start(); }
 void done(){ findViewById(R.id.prog).setVisibility(View.GONE); ((Button)findViewById(R.id.btnSend)).setEnabled(true); }
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();} }
