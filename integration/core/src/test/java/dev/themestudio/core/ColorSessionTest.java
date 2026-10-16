package dev.themestudio.core;

import java.util.Collections;
import java.util.Map;

/** JVM-only tests, intentionally no Android toolchain or APK tasks. */
public final class ColorSessionTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Assertion " + assertions); }
    private static void fails(Runnable action) { boolean failed=false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { failed=true; } check(failed); }
    public static void main(String[] args) throws Exception {
        ColorSession s = new ColorSession(Collections.emptyMap(), 2);
        check(s.getOverride("chat_inBubble") == null);
        s.begin("chat_inBubble"); s.preview(0x80123456);
        check(s.getOverride("chat_inBubble") == 0x80123456);
        check(!s.committedSnapshot().containsKey("chat_inBubble"));
        fails(s::undo); fails(() -> s.begin("chat_outBubble"));
        s.cancel(); check(s.getOverride("chat_inBubble") == null); check(!s.undo());
        s.begin("chat_inBubble"); s.preview(1); s.preview(2); s.commit();
        check(s.undo()); check(s.getOverride("chat_inBubble") == null); check(!s.undo());
        check(s.redo()); check(s.getOverride("chat_inBubble") == 2);
        check(s.committedSnapshot().get("chat_inBubble") == 2);
        s.begin("chat_inBubble"); s.previewDefault(); s.cancel(); check(s.getOverride("chat_inBubble") == 2);
        s.begin("chat_inBubble"); s.previewDefault(); s.commit(); check(s.getOverride("chat_inBubble") == null);
        check(s.undo()); check(s.getOverride("chat_inBubble") == 2);
        s.begin("chat_inBubble"); s.preview(3); s.commit(); check(!s.redo());
        for(int i=4;i<8;i++){s.begin("chat_inBubble");s.preview(i);s.commit();}
        check(s.undo());check(s.undo());check(!s.undo());
        Map<String,Integer> snapshot=s.snapshot();
        s.begin("chat_inBubble");s.preview(99);s.cancel();check(snapshot.equals(s.snapshot()));
        s.begin("chat_inBubble");s.commit();check(!s.undo());
        check(ColorSession.parseArgb("#00000000")==0);check(ColorSession.parseArgb("#FFFFFFFF")==-1);
        check(ColorSession.parseArgb("#80123456")==0x80123456);check(ColorSession.formatArgb(-1).equals("#FFFFFFFF"));
        fails(() -> ColorSession.parseArgb("#FFFFFF"));fails(() -> ColorSession.parseArgb("#GG000000"));
        fails(() -> s.begin("x\nWPS"));fails(s::commit);
        boolean[] rejected={false};Thread thread=new Thread(()->{try{s.snapshot();}catch(IllegalStateException e){rejected[0]=true;}});thread.start();thread.join();check(rejected[0]);
        System.out.println("ColorSession: "+assertions+" assertions passed");
    }
}
