package com.kamisakyy.cutpro;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

/** Scales 16-bit PCM audio, used for per-clip and music volume. */
public final class GainAudioProcessor extends BaseAudioProcessor {

    private final float gain;

    public GainAudioProcessor(float gain) {
        this.gain = Math.max(0f, Math.min(4f, gain));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        int encoding = inputAudioFormat.encoding;
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining < 4) {
            return;
        }
        int encoding = inputAudioFormat.encoding;
        ByteBuffer output = replaceOutputBuffer(remaining);
        if (encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4) {
                float sample = inputBuffer.getFloat() * gain;
                output.putFloat(Math.max(-1f, Math.min(1f, sample)));
            }
        } else {
            while (inputBuffer.remaining() >= 2) {
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
        output.flip();
    }
}
