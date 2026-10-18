package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.net.Uri; import android.os.Bundle; import android.webkit.*; import android.widget.*;
public class BrowserActivity extends Activity{ WebView web;
 protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_browser); web=findViewById(R.id.web); String url=getIntent().getStringExtra("url"); if(url==null||url.isEmpty())url="https://app.uniswap.org/swap";
  WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true);
  web.setWebViewClient(new WebViewClient(){ public void onPageFinished(WebView v,String u){ inject(); } });
  web.setWebChromeClient(new WebChromeClient(){ public void onProgressChanged(WebView v,int p){ ((ProgressBar)findViewById(R.id.prog)).setProgress(p); } });
  web.addJavascriptInterface(new Bridge(),"NovaBridge");
  ((EditText)findViewById(R.id.etUrl)).setText(url); web.loadUrl(url);
  findViewById(R.id.btnGo).setOnClickListener(v->{ String u=((EditText)findViewById(R.id.etUrl)).getText().toString().trim(); if(!u.startsWith("http"))u="https://"+u; web.loadUrl(u); }); }
 void inject(){ String addr=Keys.evmAddress(this); long chain=Networks.current(this).chainId; String js="javascript:(function(){try{window.ethereum={isMetaMask:true,isNova:true,selectedAddress:'"+addr+"',chainId:'0x"+Long.toHexString(chain)+"',request:function(a){if(a.method==='eth_requestAccounts'||a.method==='eth_accounts')return Promise.resolve(['"+addr+"']);if(a.method==='eth_chainId')return Promise.resolve('0x"+Long.toHexString(chain)+"');if(a.method==='net_version')return Promise.resolve('"+chain+"');if(window.NovaBridge)NovaBridge.request(a.method,JSON.stringify(a.params||[]));return Promise.resolve(null);},on:function(){},enable:function(){return Promise.resolve(['"+addr+"']);}};window.web3={eth:{accounts:['"+addr+"']}};console.log('Nova injected');}catch(e){}})()"; web.loadUrl(js); }
 public class Bridge{ @JavascriptInterface public void request(String method,String params){ runOnUiThread(()->Toast.makeText(BrowserActivity.this,"dApp запрос: "+method+" — подтвердите в кошельке (Отправить)",Toast.LENGTH_LONG).show()); if(method.equals("eth_sendTransaction")){ Intent i=new Intent(BrowserActivity.this,SendActivity.class); i.putExtra("mode","evm"); startActivity(i); } } }
 public void onBackPressed(){ if(web.canGoBack())web.goBack(); else super.onBackPressed(); } }
