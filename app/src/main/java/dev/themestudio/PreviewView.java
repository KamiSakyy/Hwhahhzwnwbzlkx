package dev.themestudio;

import android.content.Context;
import android.util.AttributeSet;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Lightweight approximation. Official Telegram UI is preserved under upstream/, not linked here. */
public class PreviewView extends View {
 public interface Selection {void select(String key);}
 public Selection selection;
 public boolean dialogs;
 public MainActivity host;
 public Bitmap wallpaper;
 private final Paint p=new Paint(3);
 private final ArrayList<Hit> hits=new ArrayList<>();
 private float scale;
 private static class Hit {RectF r;String k;Hit(RectF r,String k){this.r=r;this.k=k;}}
 public PreviewView(Context c,AttributeSet a){super(c,a);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setContentDescription("Предпросмотр темы. Нажмите на элемент для изменения цвета.");}
 private int color(String k){return host==null?0xffaaaaaa:host.color(k);}
 private void box(Canvas c,float l,float t,float r,float b,float radius,String key){p.setColor(color(key));RectF rect=new RectF(l,t,r,b);c.drawRoundRect(rect,radius,radius,p);hits.add(new Hit(rect,key));}
 private void text(Canvas c,String text,float x,float y,float size,String key){p.setColor(color(key));p.setTypeface(Typeface.DEFAULT);p.setTextSize(size);c.drawText(text,x,y,p);hits.add(new Hit(new RectF(x,y-size,x+p.measureText(text),y+5),key));}
 @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(host==null)return;hits.clear();scale=getWidth()/360f;float h=getHeight()/scale;canvas.save();canvas.scale(scale,scale);
  box(canvas,0,0,360,h,12,dialogs?"windowBackgroundWhite":"chat_wallpaper");
  if(!dialogs&&wallpaper!=null){float f=Math.max(360f/wallpaper.getWidth(),h/wallpaper.getHeight());float w=wallpaper.getWidth()*f,b=wallpaper.getHeight()*f;canvas.drawBitmap(wallpaper,null,new RectF((360-w)/2,(h-b)/2,(360+w)/2,(h+b)/2),p);}
  box(canvas,0,0,360,62,0,"actionBarDefault");
  text(canvas,"‹",16,40,32,"actionBarDefaultIcon");
  text(canvas,dialogs?"Telegram":"Александра",58,28,18,"actionBarDefaultTitle");
  text(canvas,dialogs?"Ваши сообщения":"была недавно",58,49,12,"actionBarDefaultSubtitle");
  text(canvas,"⋮",333,39,25,"actionBarDefaultIcon");
  if(dialogs){String[] names={"Александра","Избранное","Дизайн и вдохновение","Команда Telegram"};String[] messages={"Какая красивая тема!","Ваши сохранённые сообщения","Новая палитра уже здесь","Добро пожаловать"};
   for(int i=0;i<4;i++){float y=64+i*73;if(y+72>h)break;box(canvas,0,y,360,y+72,0,"windowBackgroundWhite");box(canvas,14,y+12,62,y+60,24,"avatar_backgroundBlue");text(canvas,names[i].substring(0,1),30,y+44,22,"avatar_text");text(canvas,names[i],76,y+29,16,"chats_name");text(canvas,messages[i],76,y+52,13,"chats_message");text(canvas,"12:40",310,y+25,10,"chats_date");box(canvas,76,y+71,360,y+72,0,"divider");}
  } else {
   float y=82;
   box(canvas,122,y,238,y+24,12,"chat_serviceBackground");text(canvas,"16 октября",145,y+17,12,"chat_serviceText");y+=40;
   box(canvas,10,y,286,y+76,14,"chat_inBubble");text(canvas,"Привет! Создадим новую тему?",22,y+28,15,"chat_messageTextIn");text(canvas,"Нажми на любой цвет ✨",22,y+50,15,"chat_messageTextIn");text(canvas,"12:40",242,y+66,10,"chat_inTimeText");y+=88;
   box(canvas,67,y,350,y+68,14,"chat_outBubble");text(canvas,"Да! Цвета, детали и настроение.",79,y+27,14,"chat_messageTextOut");text(canvas,"Всё будет по-моему.",79,y+48,14,"chat_messageTextOut");text(canvas,"12:41 ✓✓",287,y+60,9,"chat_outTimeText");y+=80;
   if(y+78<h-58){box(canvas,10,y,285,y+76,14,"chat_inBubble");text(canvas,"Telegram · Theme Studio",22,y+25,14,"chat_messageLinkIn");text(canvas,"Измени прозрачность и экспортируй",22,y+47,13,"chat_messageTextIn");text(canvas,"готовую тему в .attheme",22,y+64,13,"chat_messageTextIn");}
   box(canvas,0,h-54,360,h,0,"chat_messagePanelBackground");text(canvas,"☺",12,h-20,24,"chat_messagePanelIcons");text(canvas,"Сообщение",48,h-20,16,"chat_messagePanelHint");text(canvas,"➤",322,h-18,25,"chat_messagePanelSend");
  }canvas.restore();
 }
 @Override public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN)return true;if(e.getAction()==MotionEvent.ACTION_UP){performClick();for(int i=hits.size()-1;i>=0;i--){Hit hit=hits.get(i);if(hit.r.contains(e.getX()/scale,e.getY()/scale)){if(selection!=null)selection.select(hit.k);break;}}return true;}return true;}
 @Override public boolean performClick(){super.performClick();return true;}
}
