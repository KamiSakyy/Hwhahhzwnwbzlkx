package com.kamisakyy.cutpro;

import androidx.media3.common.Effect;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;

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

    public static List<Effect> effectsFor(int index) {
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
        return list;
    }

    public static String name(int index) {
        if (index < 0 || index >= NAMES.length) {
            return NAMES[0];
        }
        return NAMES[index];
    }
}
