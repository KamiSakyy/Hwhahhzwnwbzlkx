package dev.themestudio;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class ThemeDocumentTest {
 private ThemeDocument read(String s) throws Exception{return ThemeDocument.read(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));}
 @Test public void signedColorsAndUnknownKeysSurvive() throws Exception {ThemeDocument d=read("chat_inBubble=-1\nfuture_key=123456\n");assertEquals(Integer.valueOf(-1),d.colors.get("chat_inBubble"));assertEquals(Integer.valueOf(123456),d.colors.get("future_key"));}
 @Test public void acceptsMissingLastNewline() throws Exception {assertEquals(Integer.valueOf(-16777216),read("chat_outBubble=-16777216").colors.get("chat_outBubble"));}
 @Test public void binaryWallpaperRoundTrip() throws Exception {ThemeDocument d=new ThemeDocument();d.colors.put("chat_inBubble",-1);d.wallpaper=new byte[]{(byte)255,0,10,61,4,7};ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);ThemeDocument copy=ThemeDocument.read(new ByteArrayInputStream(out.toByteArray()));assertEquals(d.colors,copy.colors);assertArrayEquals(d.wallpaper,copy.wallpaper);}
 @Test public void wallpaperLinkSurvives() throws Exception {ThemeDocument d=read("WLS=https://t.me/bg/example\n");assertEquals("https://t.me/bg/example",d.wallpaperLink);}
 @Test(expected=IOException.class) public void invalidColorRejected() throws Exception {read("chat_inBubble=broken\n");}
 @Test public void snapshotsDoNotShareColors() {ThemeDocument d=new ThemeDocument();d.colors.put("chat_inBubble",1);ThemeDocument c=d.copy();d.colors.put("chat_inBubble",2);assertEquals(Integer.valueOf(1),c.colors.get("chat_inBubble"));}
}
