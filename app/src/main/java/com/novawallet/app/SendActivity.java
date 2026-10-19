package com.novawallet.app;
import android.app.Activity; import android.os.Bundle; import android.view.View; import android.widget.*; import java.math.*; import java.util.concurrent.*;
public class SendActivity extends Activity{ ExecutorService ex=Executors.newSingleThreadExecutor(); String mode,token,symbol; int decimals=18;
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_send); mode=getIntent().getStringExtra("mode"); if(mode==null)mode="evm"; token=getIntent().getStringExtra("token"); symbol=getIntent().getStringExtra("symbol"); decimals=getIntent().getIntExtra("decimals",18);
  if(getIntent().getStringExtra("to")!=null)((EditText)findViewById(R.id.etTo)).setText(getIntent().getStringExtra("to"));
  String title=mode.equals("btc")?"Отправить BTC":mode.equals("sol")?"Отправить SOL":token!=null?"Отправить "+symbol:"Отправить "+Networks.current(this).symbol; ((TextView)findViewById(R.id.tvTitle)).setText(title);
  findViewById(R.id.btnSend).setOnClickListener(v->send()); }
 void send(){ String to=((EditText)findViewById(R.id.etTo)).getText().toString().trim(); String amtS=((EditText)findViewById(R.id.etAmt)).getText().toString().trim(); if(to.isEmpty()||amtS.isEmpty()){toast("Заполните адрес и сумму");return;}
  findViewById(R.id.prog).setVisibility(View.VISIBLE); ((Button)findViewById(R.id.btnSend)).setEnabled(false);
  ex.execute(()->{ try{ BigDecimal amt=new BigDecimal(amtS); String hash;
    if(mode.equals("btc"))hash=BtcSol.btcSend(Keys.btcPriv(this),Keys.btcAddress(this),to,amt);
    else if(mode.equals("sol"))hash=BtcSol.solSend(Keys.solSeed(this),Keys.solAddress(this),to,amt);
    else if(token!=null)hash=Evm.sendErc20(Networks.current(this),Keys.evmPriv(this),token,to,amt,decimals);
    else hash=Evm.sendEth(Networks.current(this),Keys.evmPriv(this),to,amt);
    String link=mode.equals("btc")?"https://mempool.space/tx/"+hash:mode.equals("sol")?"https://solscan.io/tx/"+hash:Networks.current(this).explorer+"/tx/"+hash;
    runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText(" Отправлено!\nХэш: "+hash+"\n"+link); done(); });
   }catch(Exception e){ runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText(" "+e.getMessage()); done(); }); } }); }
 void done(){ findViewById(R.id.prog).setVisibility(View.GONE); ((Button)findViewById(R.id.btnSend)).setEnabled(true); }
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();} }
