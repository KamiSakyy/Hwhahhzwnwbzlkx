package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.os.Bundle;
public class MainActivity extends Activity{ protected void onCreate(Bundle b){ super.onCreate(b); if(!Store.hasWallet(this)) startActivity(new Intent(this,WelcomeActivity.class)); else if(Store.hasPin(this)) startActivity(new Intent(this,LockActivity.class)); else startActivity(new Intent(this,HomeActivity.class)); finish(); } }
