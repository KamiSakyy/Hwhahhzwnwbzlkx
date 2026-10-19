package com.novawallet.app;
import android.app.Activity; import android.content.*; import android.net.Uri; import android.os.Bundle; import android.view.View; import android.widget.*; import java.math.*; import java.util.concurrent.*;
public class CreateTokenActivity extends Activity{ ExecutorService ex=Executors.newSingleThreadExecutor(); String shareText="";
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_create_token);
  // Force Sepolia for free deploy
  Store.pub(this).edit().putInt("net",0).apply();
  findViewById(R.id.btnFaucet).setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://cloud.google.com/application/web3/faucet/ethereum/Sepolia"))));
  findViewById(R.id.btnShare).setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT,shareText); startActivity(Intent.createChooser(i,"Поделиться токеном")); });
  findViewById(R.id.btnDeploy).setOnClickListener(v->deploy()); }
 void deploy(){ String name=txt(R.id.etName), sym=txt(R.id.etSymbol).toUpperCase(), sup=txt(R.id.etSupply), decS=txt(R.id.etDec); if(name.isEmpty()||sym.isEmpty()||sup.isEmpty()){toast("Заполните название, тикер и количество");return;}
  findViewById(R.id.prog).setVisibility(View.VISIBLE); ((TextView)findViewById(R.id.tvResult)).setText("⏳ Развёртываю контракт в Sepolia… (30–90 сек)");
  ex.execute(()->{ try{ int dec=decS.isEmpty()?18:Integer.parseInt(decS); Networks.Net n=Networks.ALL.get(0); String addr=Keys.evmAddress(this); BigDecimal bal=Evm.balance(n,addr); if(bal.compareTo(new BigDecimal("0.001"))<0)throw new Exception("Нужно немного SepoliaETH на газ. Нажмите кнопку крана выше, адрес: "+addr);
    String res=Evm.deployToken(n,Keys.evmPriv(this),name,sym,dec,new BigInteger(sup)); String[] p=res.split("\\|"); String hash=p[0], contract=p.length>1?p[1]:"pending";
    if(!contract.equals("pending"))Networks.addToken(this,new Networks.Token(contract,name,sym,dec,11155111));
    shareText=" Я создал свой токен "+name+" ("+sym+") в Sepolia Testnet!\nКонтракт: "+contract+"\nДобавь в MetaMask/Trust Wallet/Nova: Import token  "+contract+"\nСеть: Sepolia (ChainID 11155111)\nТранзакция: https://sepolia.etherscan.io/tx/"+hash;
    runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText(" Токен создан!\nКонтракт: "+contract+"\nХэш: "+hash+"\n\nТокен уже добавлен в ваш список активов (переключитесь на Sepolia). Поделитесь адресом контракта с друзьями — они добавят токен и вы сможете отправлять им "+sym+"!"); findViewById(R.id.btnShare).setVisibility(View.VISIBLE); findViewById(R.id.prog).setVisibility(View.GONE); });
   }catch(Exception e){ runOnUiThread(()->{ ((TextView)findViewById(R.id.tvResult)).setText(" "+e.getMessage()); findViewById(R.id.prog).setVisibility(View.GONE); }); } }); }
 String txt(int id){return ((EditText)findViewById(id)).getText().toString().trim();}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();} }
