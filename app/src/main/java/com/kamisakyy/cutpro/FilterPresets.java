package com.kamisakyy.cutpro;

import androidx.media3.common.Effect;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.ScaleAndRotateTransformation;

import java.util.ArrayList;
import java.util.List;

/** Colour filters shared by the live preview and the exporter. */
public final class FilterPresets {

    public static final String[] NAMES = {
            "Оригинал", "Ч/Б", "Негатив", "Тёплый",
            "Холодный", "Сочный", "Ночь", "Кино"
    };

    private FilterPresets() {
    }

    public static List<Effect> effectsFor(Project.Clip clip) {
        if (clip == null) {
            return new ArrayList<>();
        }
        List<Effect> list = effectsFor(clip.filter, clip.brightness, clip.contrast,
                clip.saturation, clip.warmth);
        int rotation = ((clip.rotation % 360) + 360) % 360;
        if (rotation != 0 || clip.mirror) {
            ScaleAndRotateTransformation.Builder transform =
                    new ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(rotation)
                            .setScale(clip.mirror ? -1f : 1f, 1f);
            list.add(transform.build());
        }
        return list;
    }

    public static List<Effect> effectsFor(int index) {
        return effectsFor(index, 0f, 1f, 0f, 0f);
    }

    public static List<Effect> effectsFor(int index, float brightness, float contrast,
                                          float saturation, float warmth) {
        List<Effect> list = new ArrayList<>();
        switch (index) {
            case 1:
                list.add(RgbFilter.createGrayscaleFilter());
                break;
            case 2:
                list.add(RgbFilter.createInvertedFilter());
                break;
            case 3:
                list.add(new RgbAdjustment.Builder()
                        .setRedScale(1.16f).setGreenScale(1.02f).setBlueScale(0.86f).build());
                break;
            case 4:
                list.add(new RgbAdjustment.Builder()
                        .setRedScale(0.86f).setGreenScale(1.0f).setBlueScale(1.16f).build());
                break;
            case 5:
                list.add(new RgbAdjustment.Builder()
                        .setRedScale(1.12f).setGreenScale(1.12f).setBlueScale(1.12f).build());
                break;
            case 6:
                list.add(new RgbAdjustment.Builder()
                        .setRedScale(0.62f).setGreenScale(0.68f).setBlueScale(0.86f).build());
                break;
            case 7:
                list.add(new RgbAdjustment.Builder()
                        .setRedScale(1.05f).setGreenScale(0.98f).setBlueScale(1.12f).build());
                break;
            default:
                break;
        }
        if (Math.abs(brightness) > 0.005f) {
            list.add(new Brightness(clamp(brightness, -1f, 1f)));
        }
        if (Math.abs(contrast - 1f) > 0.005f) {
            list.add(new Contrast(clamp(contrast - 1f, -1f, 1f)));
        }
        if (Math.abs(saturation) > 0.005f) {
            list.add(new HslAdjustment.Builder()
                    .adjustSaturation(clamp(saturation, -1f, 1f) * 100f)
                    .build());
        }
        if (Math.abs(warmth) > 0.005f) {
            float shift = clamp(warmth, -1f, 1f) * 0.25f;
            list.add(new RgbAdjustment.Builder()
                    .setRedScale(1f + shift)
                    .setGreenScale(1f)
                    .setBlueScale(1f - shift)
                    .build());
        }
        return list;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static String name(int index) {
        if (index < 0 || index >= NAMES.length) {
            return NAMES[0];
        }
        return NAMES[index];
    }
}
