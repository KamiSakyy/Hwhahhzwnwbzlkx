package dev.themestudio;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import android.text.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
 private final TreeMap<String,Integer> defaults=new TreeMap<>();
 private ThemeDocument doc=new ThemeDocument();
 private final ArrayDeque<ThemeDocument> history=new ArrayDeque<>();
 private PreviewView preview;
 private TextView status;
 private static final int IMPORT=10,EXPORT=11,IMAGE=12;
 public int color(String key){Integer c=doc.colors.get(key);if(c==null)c=defaults.get(key);return c==null?0xff8095a8:c;}
 @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);
  try(InputStream in=getAssets().open("defaults.json")){JSONObject j=new JSONObject(readText(in));Iterator<String> it=j.keys();while(it.hasNext()){String k=it.next();defaults.put(k,j.getInt(k));}}catch(Exception e){error(e);}
  File saved=new File(getFilesDir(),"project.attheme");if(saved.exists())try(InputStream in=new FileInputStream(saved)){doc=ThemeDocument.read(in);}catch(Exception e){error(e);}
  preview=findViewById(R.id.preview);preview.host=this;preview.selection=this::editColor;status=findViewById(R.id.status);
  findViewById(R.id.chat).setOnClickListener(v->{preview.dialogs=false;refresh();});
  findViewById(R.id.list).setOnClickListener(v->{preview.dialogs=true;refresh();});
  findViewById(R.id.keys).setOnClickListener(v->showKeys());
  findViewById(R.id.undo).setOnClickListener(v->{if(!history.isEmpty()){doc=history.pop();refreshWallpaper();save();refresh();}else toast("Нет изменений для отмены");});
  findViewById(R.id.wallpaper).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Обои").setItems(new String[]{"Выбрать фото / Google Фото","Удалить фото и ссылку","Цвет фона"},(d,n)->{if(n==0){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);launch(i,IMAGE);}if(n==1){remember();doc.wallpaper=null;doc.wallpaperLink=null;refreshWallpaper();save();refresh();}if(n==2)editColor("chat_wallpaper");}).show());
  findViewById(R.id.files).setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Проект .attheme").setItems(new String[]{"Экспортировать тему","Импортировать тему","Сбросить проект","О приложении / лицензия"},(d,n)->{if(n==0)launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"MyTheme.attheme"),EXPORT);if(n==1)launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);if(n==2)new AlertDialog.Builder(this).setMessage("Сбросить цвета и обои? Отмена доступна до закрытия приложения.").setNegativeButton("Нет",null).setPositiveButton("Сбросить",(a,b1)->{remember();doc=new ThemeDocument();refreshWallpaper();save();refresh();}).show();if(n==3)new AlertDialog.Builder(this).setTitle("Theme Studio 0.1").setMessage("Независимый редактор, не официальный клиент Telegram. Java/XML, без рекламы и доступа к сети. Палитра: DrKLO/Telegram, 13.10.2026. Предпросмотр облегчённый, не пиксельная копия. GPL-2.0. Исходники и лицензия: github.com/KamiSakyy/Hwhahhzwnwbzlkx. Ключ подписи публичный тестовый, не для доверенной production-публикации.").setPositiveButton("OK",null).show();}).show());
  refreshWallpaper();refresh();
 }
 private static String readText(InputStream in) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toString("UTF-8");}
 private void launch(Intent i,int code){try{startActivityForResult(i,code);}catch(ActivityNotFoundException e){toast("Нет приложения для выбора файлов");}}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private void error(Exception e){toast("Ошибка: "+e.getMessage());}
 private void refresh(){preview.invalidate();status.setText(defaults.size()+" стандартных цветов · "+doc.colors.size()+" изменений · автосохранение");}
 private void remember(){history.push(doc.copy());while(history.size()>40)history.removeLast();}
 private void save(){File target=new File(getFilesDir(),"project.attheme"),tmp=new File(getFilesDir(),"project.tmp");try(OutputStream out=new FileOutputStream(tmp)){doc.write(out);}catch(Exception e){error(e);return;}if(!tmp.renameTo(target))toast("Не удалось сохранить проект");}
 private void refreshWallpaper(){preview.wallpaper=doc.wallpaper==null?null:decode(doc.wallpaper);}
 private Bitmap decode(byte[] bytes){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);o.inSampleSize=1;while(o.outWidth/o.inSampleSize>2048||o.outHeight/o.inSampleSize>2048)o.inSampleSize*=2;o.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);}
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{
  if(req==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Нет доступа к файлу");doc.write(out);}toast("Тема экспортирована. Откройте .attheme в Telegram.");}
  if(req==IMPORT){ThemeDocument imported;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Нет доступа к файлу");imported=ThemeDocument.read(in);}if(imported.colors.isEmpty())throw new IOException("В файле нет цветов темы");remember();doc=imported;refreshWallpaper();save();refresh();}
  if(req==IMAGE){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Нет доступа к фото");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>16*1024*1024)throw new IOException("Выберите фото до 16 МБ");bytes.write(b,0,n);}}Bitmap image=decode(bytes.toByteArray());if(image==null)throw new IOException("Формат изображения не поддерживается");ByteArrayOutputStream jpg=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,88,jpg);remember();doc.wallpaper=jpg.toByteArray();doc.wallpaperLink=null;preview.wallpaper=image;save();refresh();}
 }catch(Exception e){error(e);}}
 private void showKeys(){LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);EditText search=new EditText(this);search.setSingleLine();search.setHint("Поиск: bubble, text, actionBar…");panel.addView(search);ListView list=new ListView(this);panel.addView(list,new LinearLayout.LayoutParams(-1,(int)(380*getResources().getDisplayMetrics().density)));TreeSet<String> set=new TreeSet<>(defaults.keySet());set.addAll(doc.colors.keySet());ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>(set));list.setAdapter(adapter);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Официальные ключи цвета").setView(panel).setNegativeButton("Закрыть",null).create();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){adapter.getFilter().filter(s);}public void afterTextChanged(Editable e){}});list.setOnItemClickListener((a,v,p,id)->{String key=adapter.getItem(p);dialog.dismiss();editColor(key);});dialog.show();}
 private void editColor(String key){int original=color(key);boolean existed=doc.colors.containsKey(key);ThemeDocument before=doc.copy();LinearLayout panel=new LinearLayout(this);panel.setPadding(32,8,32,8);panel.setOrientation(1);TextView sample=new TextView(this);sample.setText("  Предпросмотр цвета  ");sample.setHeight(72);panel.addView(sample);EditText hex=new EditText(this);hex.setSingleLine();hex.setHint("#AARRGGBB");panel.addView(hex);SeekBar[] bars=new SeekBar[4];TextView[] labels=new TextView[4];String[] names={"R · красный","G · зелёный","B · синий","A · непрозрачность"};boolean[] updating={false};int[] channels={Color.red(original),Color.green(original),Color.blue(original),Color.alpha(original)};
  Runnable apply=()->{int c=Color.argb(bars[3].getProgress(),bars[0].getProgress(),bars[1].getProgress(),bars[2].getProgress());doc.colors.put(key,c);sample.setBackgroundColor(c);for(int i=0;i<4;i++)labels[i].setText(names[i]+": "+bars[i].getProgress());if(!updating[0]){updating[0]=true;hex.setText(String.format(Locale.ROOT,"#%08X",c));updating[0]=false;}preview.invalidate();};
  for(int i=0;i<4;i++){labels[i]=new TextView(this);panel.addView(labels[i]);bars[i]=new SeekBar(this);bars[i].setMax(255);bars[i].setProgress(channels[i]);panel.addView(bars[i]);}
  for(SeekBar bar:bars)bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int p,boolean user){if(user)apply.run();}});
  hex.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){if(updating[0])return;String v=s.toString();if(!v.matches("#[0-9a-fA-F]{8}"))return;try{int c=Color.parseColor(v);updating[0]=true;bars[0].setProgress(Color.red(c));bars[1].setProgress(Color.green(c));bars[2].setProgress(Color.blue(c));bars[3].setProgress(Color.alpha(c));apply.run();updating[0]=false;}catch(Exception ignored){updating[0]=false;}}public void afterTextChanged(Editable e){}});
  // Initialize without adding a modification to the document.
  apply.run();if(!existed)doc.colors.remove(key);
  ScrollView scroll=new ScrollView(this);scroll.addView(panel);
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle(key).setView(scroll).setNegativeButton("Отмена",(d,w)->{}).setNeutralButton("Стандартный",(d,w)->{history.push(before);doc.colors.remove(key);save();refresh();}).setPositiveButton("Применить",null).create();boolean[] accepted={false};
  dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!hex.getText().toString().matches("#[0-9a-fA-F]{8}")){hex.setError("Введите #AARRGGBB");return;}accepted[0]=true;history.push(before);while(history.size()>40)history.removeLast();doc.colors.put(key,Color.parseColor(hex.getText().toString()));save();refresh();dialog.dismiss();});dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{accepted[0]=true;history.push(before);doc.colors.remove(key);save();refresh();dialog.dismiss();});});
  dialog.setOnDismissListener(d->{if(!accepted[0]){doc=before;refresh();}});dialog.show();
 }
}
