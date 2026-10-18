package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.os.Bundle;
public class WelcomeActivity extends Activity{ protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_welcome); findViewById(R.id.btnCreate).setOnClickListener(v->startActivity(new Intent(this,CreateWalletActivity.class))); findViewById(R.id.btnImport).setOnClickListener(v->startActivity(new Intent(this,ImportActivity.class))); } }
