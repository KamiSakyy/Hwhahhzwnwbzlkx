package com.novawallet.app;
import android.app.Activity; import android.content.*; import android.os.Bundle; import android.widget.*; 
public class CreateWalletActivity extends Activity{ String mnemonic; protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_create); mnemonic=WalletCore.newMnemonic(); ((TextView)findViewById(R.id.tvMnemonic)).setText(mnemonic);
 findViewById(R.id.btnCopy).setOnClickListener(v->{ ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("seed",mnemonic)); Toast.makeText(this,"Скопировано. Удалите из буфера после записи!",Toast.LENGTH_LONG).show(); });
 findViewById(R.id.btnDone).setOnClickListener(v->{ String pin=((EditText)findViewById(R.id.etPin)).getText().toString(); if(pin.length()<4){toast("PIN минимум 4 цифры");return;} if(!((CheckBox)findViewById(R.id.cbSaved)).isChecked()){toast("Подтвердите, что записали фразу");return;} Store.saveMnemonic(this,mnemonic); Store.setPin(this,pin); startActivity(new Intent(this,HomeActivity.class)); finishAffinity(); }); }
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();} }
