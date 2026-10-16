package dev.themestudio;

import android.graphics.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android .attheme codec; binary wallpaper must never be decoded as text. */
public final class ThemeDocument {
 public final TreeMap<String,Integer> colors = new TreeMap<>();
 public byte[] wallpaper;
 public String wallpaperLink;
 public static ThemeDocument read(InputStream in) throws IOException {
  ThemeDocument d=new ThemeDocument(); ByteArrayOutputStream line=new ByteArrayOutputStream(); int ch,total=0;
  while((ch=in.read())!=-1) {
   if(++total>16*1024*1024) throw new IOException("Файл больше 16 МБ");
   if(ch!='\n') {line.write(ch); if(line.size()>8192) throw new IOException("Слишком длинная строка"); continue;}
   String s=line.toString("UTF-8").trim(); line.reset();
   if(s.equals("WPS")) {
    ByteArrayOutputStream image=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
    while((n=in.read(buf))!=-1) {total+=n;if(total>16*1024*1024)throw new IOException("Обои больше 16 МБ");image.write(buf,0,n);}
    byte[] bytes=image.toByteArray(); int end=bytes.length;
    byte[] marker="\nWPE\n".getBytes(StandardCharsets.UTF_8);
    if(end>=marker.length) {boolean match=true;for(int i=0;i<marker.length;i++)if(bytes[end-marker.length+i]!=marker[i])match=false;if(match)end-=marker.length;}
    d.wallpaper=Arrays.copyOf(bytes,end); break;
   }
   parse(d,s);
  }
  if(line.size()>0)parse(d,line.toString("UTF-8").trim());
  return d;
 }
 private static void parse(ThemeDocument d,String s) throws IOException {
  if(s.startsWith("WLS=")){d.wallpaperLink=s.substring(4);return;}
  int p=s.indexOf('=');if(p<=0)return;
  String key=s.substring(0,p).trim(),v=s.substring(p+1).trim();
  if(!key.matches("[A-Za-z0-9_]+"))return;
  try { d.colors.put(key,v.startsWith("#")?Color.parseColor(v):(int)Long.parseLong(v)); }
  catch(IllegalArgumentException e){throw new IOException("Некорректный цвет: "+key);}
 }
 public void write(OutputStream out) throws IOException {
  for(Map.Entry<String,Integer> e:colors.entrySet())out.write((e.getKey()+"="+e.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
  if(wallpaper!=null){out.write("WPS\n".getBytes(StandardCharsets.UTF_8));out.write(wallpaper);out.write("\nWPE\n".getBytes(StandardCharsets.UTF_8));}
  else if(wallpaperLink!=null)out.write(("WLS="+wallpaperLink+"\n").getBytes(StandardCharsets.UTF_8));
 }
 public ThemeDocument copy(){ThemeDocument d=new ThemeDocument();d.colors.putAll(colors);d.wallpaper=wallpaper;d.wallpaperLink=wallpaperLink;return d;}
}
