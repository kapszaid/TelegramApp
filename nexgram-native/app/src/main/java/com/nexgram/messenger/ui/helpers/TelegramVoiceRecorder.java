package com.nexgram.messenger.ui.helpers;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram Voice Recorder
 *
 * Implements Milestone 2.9 — Voice Message Recording:
 * - Real microphone recording through MediaRecorder
 * - Waveform sample collection from live max amplitude
 * - Safe timer tick callbacks
 * - Graceful stop and cancel cleanup
 */
public class TelegramVoiceRecorder {
    private static final String TAG = "TelegramVoiceRecorder";

    private static volatile TelegramVoiceRecorder instance;

    public static TelegramVoiceRecorder getInstance() {
        if (instance == null) {
            synchronized (TelegramVoiceRecorder.class) {
                if (instance == null) {
                    instance = new TelegramVoiceRecorder();
                }
            }
        }
        return instance;
    }

    public interface OnRecordingListener {
        void onTick(int durationSeconds, int amplitude);
        void onError(String message);
    }

    public interface RecordCallback {
        void onTimerTick(int durationSeconds);
        void onRecordingFinished(File voiceFile, int durationSeconds, byte[] waveform);
        void onRecordingError(String message);
    }

    public static class VoiceRecordingResult {
        public final File file;
        public final int durationSeconds;
        public final byte[] waveform;

        public VoiceRecordingResult(File file, int durationSeconds, byte[] waveform) {
            this.file = file;
            this.durationSeconds = durationSeconds;
            this.waveform = waveform;
        }
    }

    private MediaRecorder mediaRecorder;
    private File currentRecordFile;
    private boolean isRecording = false;
    private long startTimeMillis = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> amplitudeSamples = new ArrayList<>();
    private OnRecordingListener listener;
    private RecordCallback recordCallback;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecording || mediaRecorder == null) return;
            try {
                int maxAmp = mediaRecorder.getMaxAmplitude();
                amplitudeSamples.add(maxAmp);

                long elapsed = System.currentTimeMillis() - startTimeMillis;
                int seconds = (int) (elapsed / 1000);

                if (listener != null) {
                    listener.onTick(seconds, maxAmp);
                }

                handler.postDelayed(this, 100);
            } catch (Exception e) {
                Log.e(TAG, "Error sampling amplitude: " + e.getMessage());
            }
        }
    };

    public boolean startRecording(Context context, OnRecordingListener listener) {
        if (context == null || isRecording) return false;
        this.listener = listener;

        try {
            File outDir = new File(context.getCacheDir(), "voice_out");
            if (!outDir.exists()) {
                outDir.mkdirs();
            }

            // On modern Android (API 29+), OGG with OPUS or MP4 with AAC
            boolean useOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
            String ext = useOgg ? ".ogg" : ".m4a";
            currentRecordFile = new File(outDir, "voice_" + System.currentTimeMillis() + ext);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(context);
            } else {
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (useOgg) {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            } else {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }

            mediaRecorder.setAudioSamplingRate(48000);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setOutputFile(currentRecordFile.getAbsolutePath());

            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            startTimeMillis = System.currentTimeMillis();
            amplitudeSamples.clear();

            handler.post(timerRunnable);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage(), e);
            cleanupRecorder();
            if (listener != null) {
                listener.onError("Failed to initialize microphone: " + e.getMessage());
            }
            return false;
        }
    }

    public boolean startRecording(Context context, RecordCallback callback) {
        this.recordCallback = callback;
        return startRecording(context, new OnRecordingListener() {
            @Override
            public void onTick(int durationSeconds, int amplitude) {
                if (recordCallback != null) {
                    recordCallback.onTimerTick(durationSeconds);
                }
            }

            @Override
            public void onError(String message) {
                if (recordCallback != null) {
                    recordCallback.onRecordingError(message);
                }
            }
        });
    }

    public VoiceRecordingResult stopRecording() {
        if (!isRecording) return null;
        isRecording = false;
        handler.removeCallbacks(timerRunnable);

        long elapsed = System.currentTimeMillis() - startTimeMillis;
        int durationSec = Math.max(1, (int) (elapsed / 1000));

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder: " + e.getMessage());
        } finally {
            cleanupRecorder();
        }

        VoiceRecordingResult result = null;
        if (currentRecordFile != null && currentRecordFile.exists() && currentRecordFile.length() > 0) {
            byte[] waveform = computeTelegramWaveform(amplitudeSamples);
            File fileToSend = currentRecordFile;
            currentRecordFile = null;
            result = new VoiceRecordingResult(fileToSend, durationSec, waveform);
        } else {
            cancelRecording();
        }

        if (recordCallback != null) {
            if (result != null) {
                recordCallback.onRecordingFinished(result.file, result.durationSeconds, result.waveform);
            } else {
                recordCallback.onRecordingFinished(null, 0, new byte[0]);
            }
            recordCallback = null;
        }

        return result;
    }

    public void cancelRecording() {
        isRecording = false;
        recordCallback = null;
        handler.removeCallbacks(timerRunnable);
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (Exception ignored) {
        } finally {
            cleanupRecorder();
        }

        if (currentRecordFile != null && currentRecordFile.exists()) {
            currentRecordFile.delete();
            currentRecordFile = null;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }

    private void cleanupRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception ignored) {}
            mediaRecorder = null;
        }
    }

    /**
     * Normalizes raw amplitudes into a 100-byte Telegram waveform array (values 0-31).
     */
    private byte[] computeTelegramWaveform(List<Integer> samples) {
        int targetLen = 100;
        byte[] waveform = new byte[targetLen];
        if (samples == null || samples.isEmpty()) {
            return waveform;
        }

        int max = 1;
        for (int amp : samples) {
            if (amp > max) max = amp;
        }

        for (int i = 0; i < targetLen; i++) {
            int srcIndex = (int) (((float) i / targetLen) * samples.size());
            if (srcIndex >= samples.size()) srcIndex = samples.size() - 1;
            int amp = samples.get(srcIndex);
            waveform[i] = (byte) Math.min(31, Math.max(0, (amp * 31) / max));
        }

        return waveform;
    }
}
