package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.os.Bundle; import android.widget.*;
public class ImportActivity extends Activity{ protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_import);
 findViewById(R.id.btnImport).setOnClickListener(v->{ String s=((EditText)findViewById(R.id.etSecret)).getText().toString().trim(); String pin=((EditText)findViewById(R.id.etPin)).getText().toString(); if(pin.length()<4){toast("PIN минимум 4");return;}
  if(s.startsWith("0x")&&s.length()>=64){ Store.savePriv(this,s); } else if(WalletCore.validMnemonic(s)){ Store.saveMnemonic(this,s); } else { toast("Неверная фраза (нужно 12/24 слова BIP39) или ключ"); return; }
  Store.setPin(this,pin); startActivity(new Intent(this,HomeActivity.class)); finishAffinity(); }); }
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();} }
