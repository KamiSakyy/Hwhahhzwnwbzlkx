package com.kamisakyy.cutpro;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/**
 * Smooth gain ramp at the start and the end of a stream.
 *
 * <p>Used for «плавное появление / затухание» of clip sound and of the music track.
 * Works on 16-bit PCM and on float PCM; other encodings are passed through untouched so
 * that an exotic source never breaks the whole export.
 */
public final class FadeAudioProcessor extends BaseAudioProcessor {

    private final long fadeInUs;
    private final long fadeOutUs;
    private final long streamDurationUs;

    private long processedFrames;
    private float gain = 1f;
    private boolean passthrough;

    /**
     * @param fadeInMs        ramp up over this many milliseconds
     * @param fadeOutMs       ramp down over this many milliseconds before the stream ends
     * @param streamDurationMs total length of the stream; 0 disables the fade-out ramp
     */
    public FadeAudioProcessor(long fadeInMs, long fadeOutMs, long streamDurationMs) {
        this.fadeInUs = Math.max(0L, fadeInMs) * 1000L;
        this.fadeOutUs = Math.max(0L, fadeOutMs) * 1000L;
        this.streamDurationUs = Math.max(0L, streamDurationMs) * 1000L;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        int encoding = inputAudioFormat.encoding;
        passthrough = encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT;
        processedFrames = 0;
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        return super.isActive() && !passthrough && (fadeInUs > 0 || fadeOutUs > 0);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining < 2) {
            return;
        }
        int frameSize = inputAudioFormat.bytesPerFrame;
        if (frameSize <= 0) {
            frameSize = 2;
        }
        if (passthrough || (fadeInUs <= 0 && fadeOutUs <= 0)) {
            ByteBuffer output = replaceOutputBuffer(remaining);
            output.put(inputBuffer);
            output.flip();
            return;
        }

        int encoding = inputAudioFormat.encoding;
        ByteBuffer output = replaceOutputBuffer(remaining);
        while (inputBuffer.remaining() >= frameSize) {
            long timeUs = processedFrames * 1_000_000L / Math.max(1, inputAudioFormat.sampleRate);
            gain = gainAt(timeUs);
            if (encoding == C.ENCODING_PCM_FLOAT) {
                int frames = frameSize / 4;
                for (int i = 0; i < frames && inputBuffer.remaining() >= 4; i++) {
                    float sample = inputBuffer.getFloat() * gain;
                    output.putFloat(Math.max(-1f, Math.min(1f, sample)));
                }
            } else {
                int frames = frameSize / 2;
                for (int i = 0; i < frames && inputBuffer.remaining() >= 2; i++) {
                    short sample = inputBuffer.getShort();
                    int value = Math.round(sample * gain);
                    if (value > 32767) {
                        value = 32767;
                    } else if (value < -32768) {
                        value = -32768;
                    }
                    output.putShort((short) value);
                }
            }
            processedFrames++;
        }
        // Trailing partial frame: copy verbatim so nothing is lost.
        while (inputBuffer.remaining() > 0 && output.remaining() > 0) {
            output.put(inputBuffer.get());
        }
        output.flip();
    }

    private float gainAt(long timeUs) {
        float value = 1f;
        if (fadeInUs > 0 && timeUs < fadeInUs) {
            value = Math.min(value, timeUs / (float) fadeInUs);
        }
        if (fadeOutUs > 0 && streamDurationUs > 0) {
            long fadeOutStart = Math.max(0L, streamDurationUs - fadeOutUs);
            if (timeUs > fadeOutStart) {
                long span = Math.max(1L, streamDurationUs - fadeOutStart);
                float remainingRatio = 1f - (timeUs - fadeOutStart) / (float) span;
                value = Math.min(value, Math.max(0f, remainingRatio));
            }
        }
        return Math.max(0f, Math.min(1f, value));
    }
}
