package com.novawallet.app;
import android.app.Activity; import android.content.Intent; import android.graphics.PorterDuff; import android.view.View; import android.widget.*;
public class NavHelper{
 public static void bind(Activity a,String active){
  bindOne(a,R.id.navHome,R.id.navHomeIcon,"home",active); bindOne(a,R.id.navHistory,R.id.navHistoryIcon,"history",active); bindOne(a,R.id.navChat,R.id.navChatIcon,"chat",active); bindOne(a,R.id.navMore,R.id.navMoreIcon,"more",active); }
 static void bindOne(Activity a,int id,int iconId,String key,String active){ View v=a.findViewById(id); if(v==null)return; boolean on=key.equals(active);
   ImageView iv=a.findViewById(iconId); if(iv!=null)iv.setColorFilter(on?0xFFFFFFFF:0xFF737373,PorterDuff.Mode.SRC_IN);
   LinearLayout ll=(LinearLayout)v; if(ll.getChildCount()>1){ TextView tv=(TextView)ll.getChildAt(1); tv.setTextColor(on?0xFFFFFFFF:0xFF737373); if(on)tv.setTypeface(null,android.graphics.Typeface.BOLD); }
   v.setOnClickListener(x->{ if(on)return; Intent i=null;
     if(key.equals("home"))i=new Intent(a,HomeActivity.class); else if(key.equals("history"))i=new Intent(a,HistoryActivity.class); else if(key.equals("chat"))i=new Intent(a,MessengerActivity.class); else i=new Intent(a,ToolsActivity.class);
     if(i!=null){ if(a instanceof HistoryActivity)i.putExtra("mode",((HistoryActivity)a).mode); a.startActivity(i); a.overridePendingTransition(android.R.anim.fade_in,android.R.anim.fade_out); } }); }
}
