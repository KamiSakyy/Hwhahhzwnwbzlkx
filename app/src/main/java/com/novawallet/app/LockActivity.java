package com.novawallet.app;
import android.content.Intent; import android.os.Bundle; import android.widget.*; import androidx.appcompat.app.AppCompatActivity; import androidx.biometric.*;
import java.util.concurrent.Executors;
public class LockActivity extends AppCompatActivity{ protected void onCreate(Bundle b){ super.onCreate(b); setContentView(R.layout.activity_lock);
 findViewById(R.id.btnUnlock).setOnClickListener(v->{ if(Store.checkPin(this,((EditText)findViewById(R.id.etPin)).getText().toString()))open(); else Toast.makeText(this,"Неверный PIN",Toast.LENGTH_SHORT).show(); });
 findViewById(R.id.btnBio).setOnClickListener(v->bio()); if(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)==BiometricManager.BIOMETRIC_SUCCESS) bio(); }
 void bio(){ BiometricPrompt p=new BiometricPrompt(this,Executors.newSingleThreadExecutor(),new BiometricPrompt.AuthenticationCallback(){ public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){ runOnUiThread(()->open()); } }); p.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle("Nova Wallet").setSubtitle("Подтвердите вход").setNegativeButtonText("PIN").build()); }
 void open(){ startActivity(new Intent(this,HomeActivity.class)); finish(); } }
