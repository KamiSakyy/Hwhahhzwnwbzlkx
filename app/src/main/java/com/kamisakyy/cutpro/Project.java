package com.kamisakyy.cutpro;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One editing project: an ordered list of clips plus a music track. */
public class Project {

    public String id;
    public String name;
    public long updatedAt;
    public float aspect = 16f / 9f;
    public String musicUri;
    public String musicName;
    public float musicVolume = 0.55f;
    /** Mutes every clip's original sound while keeping the music track. */
    public boolean muteOriginal;
    public final List<Clip> clips = new ArrayList<>();

    public Project() {
        id = UUID.randomUUID().toString();
        name = "Проект";
        updatedAt = System.currentTimeMillis();
    }

    public long durationMs() {
        long total = 0;
        for (Clip c : clips) {
            total += c.outputDurationMs();
        }
        return total;
    }

    public long startOfClip(int index) {
        long t = 0;
        for (int i = 0; i < index && i < clips.size(); i++) {
            t += clips.get(i).outputDurationMs();
        }
        return t;
    }

    public int clipIndexAt(long timeMs) {
        if (clips.isEmpty()) {
            return -1;
        }
        long t = 0;
        for (int i = 0; i < clips.size(); i++) {
            long d = clips.get(i).outputDurationMs();
            if (timeMs < t + d || i == clips.size() - 1) {
                return i;
            }
            t += d;
        }
        return 0;
    }

    public String aspectLabel() {
        if (aspect > 1.5f) {
            return "16:9";
        }
        if (Math.abs(aspect - 1f) < 0.02f) {
            return "1:1";
        }
        if (Math.abs(aspect - 0.8f) < 0.02f) {
            return "4:5";
        }
        if (aspect < 0.7f) {
            return "9:16";
        }
        return String.format(java.util.Locale.US, "%.2f:1", aspect);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("updatedAt", updatedAt);
            o.put("aspect", (double) aspect);
            o.put("musicUri", musicUri == null ? JSONObject.NULL : musicUri);
            o.put("musicName", musicName == null ? JSONObject.NULL : musicName);
            o.put("musicVolume", (double) musicVolume);
            o.put("muteOriginal", muteOriginal);
            JSONArray arr = new JSONArray();
            for (Clip c : clips) {
                arr.put(c.toJson());
            }
            o.put("clips", arr);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Project fromJson(JSONObject o) {
        Project p = new Project();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", p.name);
        p.updatedAt = o.optLong("updatedAt", p.updatedAt);
        p.aspect = (float) o.optDouble("aspect", 16f / 9f);
        p.musicUri = o.isNull("musicUri") ? null : o.optString("musicUri", null);
        p.musicName = o.isNull("musicName") ? null : o.optString("musicName", null);
        p.musicVolume = (float) o.optDouble("musicVolume", 0.55f);
        p.muteOriginal = o.optBoolean("muteOriginal", false);
        JSONArray arr = o.optJSONArray("clips");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) {
                    p.clips.add(Clip.fromJson(c));
                }
            }
        }
        return p;
    }

    /** A trimmed segment of a source video. */
    /** Default on-screen duration for imported photos. */
    public static final long PHOTO_DURATION_MS = 3000L;

    public static class Clip {
        public String id = UUID.randomUUID().toString();
        public String uri;
        public String name = "Клип";
        public long sourceDurationMs;
        public long trimStartMs;
        public long trimEndMs;
        /** Video speed: frames and the on-timeline length of the clip. */
        public float speed = 1f;
        /** Sound speed, independent of the video speed. 1 = original tempo. */
        public float audioSpeed = 1f;
        public float volume = 1f;
        public int filter;
        /** True when the clip is a still image rather than a video. */
        public boolean image;
        public float brightness;
        public float contrast = 1f;
        public float saturation;
        public float warmth;
        public final List<TextItem> texts = new ArrayList<>();

        public long trimmedDurationMs() {
            return Math.max(1L, trimEndMs - trimStartMs);
        }

        /** True when the sound of this clip is sped up or slowed down on its own. */
        public boolean hasOwnAudioSpeed() {
            return Math.abs(audioSpeed - speed) > 0.01f;
        }

        public long outputDurationMs() {
            float s = Math.max(0.1f, speed);
            return Math.max(50L, (long) (trimmedDurationMs() / s));
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("uri", uri == null ? JSONObject.NULL : uri);
                o.put("name", name);
                o.put("src", sourceDurationMs);
                o.put("trimStart", trimStartMs);
                o.put("trimEnd", trimEndMs);
                o.put("speed", (double) speed);
                o.put("audioSpeed", (double) audioSpeed);
                o.put("volume", (double) volume);
                o.put("filter", filter);
                o.put("image", image);
                o.put("brightness", (double) brightness);
                o.put("contrast", (double) contrast);
                o.put("saturation", (double) saturation);
                o.put("warmth", (double) warmth);
                JSONArray arr = new JSONArray();
                for (TextItem t : texts) {
                    arr.put(t.toJson());
                }
                o.put("texts", arr);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Clip fromJson(JSONObject o) {
            Clip c = new Clip();
            c.id = o.optString("id", c.id);
            c.uri = o.isNull("uri") ? null : o.optString("uri", null);
            c.name = o.optString("name", c.name);
            c.sourceDurationMs = o.optLong("src", 0);
            c.trimStartMs = o.optLong("trimStart", 0);
            c.trimEndMs = o.optLong("trimEnd", c.sourceDurationMs);
            c.speed = (float) o.optDouble("speed", 1f);
            c.audioSpeed = (float) o.optDouble("audioSpeed", c.speed);
            c.volume = (float) o.optDouble("volume", 1f);
            c.filter = o.optInt("filter", 0);
            c.image = o.optBoolean("image", false);
            c.brightness = (float) o.optDouble("brightness", 0d);
            c.contrast = (float) o.optDouble("contrast", 1d);
            c.saturation = (float) o.optDouble("saturation", 0d);
            c.warmth = (float) o.optDouble("warmth", 0d);
            JSONArray arr = o.optJSONArray("texts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i);
                    if (t != null) {
                        c.texts.add(TextItem.fromJson(t));
                    }
                }
            }
            return c;
        }
    }

    /** A text sticker placed on a clip for a time range. */
    public static class TextItem {
        public String id = UUID.randomUUID().toString();
        public String text = "";
        public int color = 0xFFFFFFFF;
        public float size = 0.055f;
        public int position = 1;
        public long startMs;
        public long endMs;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("text", text);
                o.put("color", color);
                o.put("size", (double) size);
                o.put("position", position);
                o.put("start", startMs);
                o.put("end", endMs);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static TextItem fromJson(JSONObject o) {
            TextItem t = new TextItem();
            t.id = o.optString("id", t.id);
            t.text = o.optString("text", "");
            t.color = o.optInt("color", 0xFFFFFFFF);
            t.size = (float) o.optDouble("size", 0.055f);
            t.position = o.optInt("position", 1);
            t.startMs = o.optLong("start", 0);
            t.endMs = o.optLong("end", 0);
            return t;
        }
    }
}
