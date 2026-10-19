package com.novawallet.app;
import android.app.Activity; import android.os.Bundle; import android.widget.*; import java.util.concurrent.*;
public class AddTokenActivity extends Activity{ ExecutorService ex=Executors.newSingleThreadExecutor();
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_add_token);
  findViewById(R.id.btnAdd).setOnClickListener(v->{ String a=((EditText)findViewById(R.id.etAddr)).getText().toString().trim(); boolean nft=((RadioButton)findViewById(R.id.rbNft)).isChecked(); if(!a.startsWith("0x")||a.length()!=42){toast("Неверный адрес контракта");return;}
   ex.execute(()->{ try{ Networks.Net n=Networks.current(this); String addr=Keys.evmAddress(this);
     if(nft){ String bal=Evm.nftBalance(n,addr,a); Store.pub(this).edit().putString("nfts",Store.pub(this).getString("nfts","")+a+"|"+n.chainId+"|"+bal+";;").apply(); runOnUiThread(()->{((TextView)findViewById(R.id.tvInfo)).setText(" NFT добавлен. Баланс коллекции: "+bal);}); }
     else { String name=Evm.erc20Info(n,addr,a,0), sym=Evm.erc20Info(n,addr,a,1), dec=Evm.erc20Info(n,addr,a,2); Networks.addToken(this,new Networks.Token(a,name,sym,Integer.parseInt(dec),n.chainId)); runOnUiThread(()->{((TextView)findViewById(R.id.tvInfo)).setText(" Добавлен: "+name+" ("+sym+"), decimals="+dec);}); }
    }catch(Exception e){ runOnUiThread(()->((TextView)findViewById(R.id.tvInfo)).setText(" "+e.getMessage())); } }); }); }
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();} }
