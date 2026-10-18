package com.novawallet.app;
import android.app.*; import android.content.*; import android.net.Uri; import android.os.Bundle; import android.widget.*; import android.graphics.Color;
public class ToolsActivity extends Activity{ LinearLayout box; TextView body;
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_tools); box=findViewById(R.id.container); body=findViewById(R.id.tvBody); String m=getIntent().getStringExtra("mode"); if(m==null)m="menu";
  if(m.equals("nft"))nft(); else if(m.equals("backup"))backup(); else menu(); }
 void title(String t){((TextView)findViewById(R.id.tvTitle)).setText(t);}
 Button btn(String t,Runnable r){ Button b=new Button(this); b.setText(t); b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2)); box.addView(b); b.setOnClickListener(v->r.run()); return b; }
 void menu(){ title("⚙ Всё остальное"); body.setText("Сети: "+Networks.ALL.size()+" EVM + Bitcoin + Solana\nТекущая: "+Networks.current(this).name);
  btn("🖼 Мои NFT",()->{Intent i=new Intent(this,ToolsActivity.class); i.putExtra("mode","nft"); startActivity(i);});
  btn("➕ Добавить токен / NFT",()->startActivity(new Intent(this,AddTokenActivity.class)));
  btn("🪙 Создать свой токен (Sepolia, бесплатно)",()->startActivity(new Intent(this,CreateTokenActivity.class)));
  btn("🌐 DeFi браузер (Uniswap и др.)",()->startActivity(new Intent(this,BrowserActivity.class)));
  btn("⇄ Обмен (Swap)",()->{Intent i=new Intent(this,BrowserActivity.class); i.putExtra("url","https://app.uniswap.org/swap"); startActivity(i);});
  btn("💳 Купить крипту (MoonPay)",()->{Intent i=new Intent(this,BrowserActivity.class); i.putExtra("url","https://buy.moonpay.com"); startActivity(i);});
  btn("🚰 Краны Sepolia (бесплатный тест-ETH)",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://cloud.google.com/application/web3/faucet/ethereum/Sepolia"))));
  btn("🔑 Показать секретную фразу / ключи",()->{Intent i=new Intent(this,ToolsActivity.class); i.putExtra("mode","backup"); startActivity(i);});
  btn("📋 Мои адреса (EVM / BTC / SOL)",()->{ body.setText("EVM: "+Keys.evmAddress(this)+"\n\nBitcoin: "+Keys.btcAddress(this)+"\n\nSolana: "+Keys.solAddress(this)); });
  btn("🌍 Все сети ("+Networks.ALL.size()+")",()->{ StringBuilder s=new StringBuilder(); for(Networks.Net n:Networks.ALL)s.append("• ").append(n.name).append(" (").append(n.chainId).append(") ").append(n.symbol).append("\n"); body.setText(s.toString()); });
  btn("🗑 Удалить кошелёк с устройства",()->new AlertDialog.Builder(this).setTitle("Удалить кошелёк?").setMessage("Без записанной фразы доступ к деньгам будет потерян навсегда!").setPositiveButton("Удалить",(d,w)->{Store.wipe(this); startActivity(new Intent(this,WelcomeActivity.class)); finishAffinity();}).setNegativeButton("Отмена",null).show());
  body.append("\n\nNova Wallet v1.0 • Non-custodial • Java + Web3j + BouncyCastle\nИсходный код токена: contracts/MyToken.sol (ERC-20)"); }
 void nft(){ title("🖼 Мои NFT"); String raw=Store.pub(this).getString("nfts",""); if(raw.isEmpty()){body.setText("Пока нет добавленных NFT.\n\nНажмите «Добавить токен / NFT», выберите NFT (ERC-721) и вставьте адрес коллекции — например, ваш токен-контракт или известную коллекцию в текущей сети.");} else { StringBuilder s=new StringBuilder(); for(String p:raw.split(";;")){ if(!p.isEmpty()){ String[] f=p.split("\\|"); s.append("Коллекция: ").append(f[0]).append("\nСеть chainId: ").append(f[1]).append("\nУ вас NFT: ").append(f[2]).append("\nОткрыть: ").append(Networks.current(this).explorer).append("/token/").append(f[0]).append("\n\n"); } } body.setText(s.toString()); }
  btn("➕ Добавить NFT коллекцию",()->startActivity(new Intent(this,AddTokenActivity.class))); }
 void backup(){ title("🔑 Секретные данные"); body.setText("⚠️ НИКОМУ не показывайте и не отправляйте эти данные!\n\nФраза:\n"+(Store.mnemonic(this).isEmpty()?"(импортирован приватный ключ)":Store.mnemonic(this))+"\n\nEVM приватный ключ:\n0x"+String.format("%064x",Keys.evmPriv(this))+"\n\nBitcoin адрес: "+Keys.btcAddress(this)+"\nSolana адрес: "+Keys.solAddress(this)); }
}
