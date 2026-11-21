package com.nexgram.messenger.ui.helpers;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;

/**
 * Telegram Audio Player
 *
 * Implements Milestone 2.9 — Voice Note and Music File Playback:
 * - Singleton playback manager guaranteeing one active audio playback
 * - Progress tracking and UI callback dispatch
 * - Audio focus friendly MediaPlayer lifecycle
 */
public class TelegramAudioPlayer {
    private static final String TAG = "TelegramAudioPlayer";
    private static TelegramAudioPlayer instance;

    public interface PlaybackListener {
        void onProgress(long messageId, int currentMs, int totalMs);
        void onPlaybackStateChanged(long messageId, boolean isPlaying);
        void onPlaybackCompleted(long messageId);
    }

    private MediaPlayer mediaPlayer;
    private long currentMessageId = -1;
    private String currentFilePath;
    private PlaybackListener currentListener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int current = mediaPlayer.getCurrentPosition();
                int total = mediaPlayer.getDuration();
                if (currentListener != null && currentMessageId != -1) {
                    currentListener.onProgress(currentMessageId, current, total);
                }
                handler.postDelayed(this, 100);
            }
        }
    };

    public static synchronized TelegramAudioPlayer getInstance() {
        if (instance == null) {
            instance = new TelegramAudioPlayer();
        }
        return instance;
    }

    public synchronized void play(long messageId, String filePath, PlaybackListener listener) {
        if (filePath == null || !new File(filePath).exists()) {
            Log.e(TAG, "Audio file does not exist: " + filePath);
            return;
        }

        // If same message is playing, toggle pause
        if (currentMessageId == messageId && mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                handler.removeCallbacks(progressRunnable);
                if (listener != null) {
                    listener.onPlaybackStateChanged(messageId, false);
                }
                return;
            } else {
                mediaPlayer.start();
                handler.post(progressRunnable);
                if (listener != null) {
                    listener.onPlaybackStateChanged(messageId, true);
                }
                return;
            }
        }

        // Stop any previous playback
        stop();

        this.currentMessageId = messageId;
        this.currentFilePath = filePath;
        this.currentListener = listener;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            );
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.start();

            if (listener != null) {
                listener.onPlaybackStateChanged(messageId, true);
            }

            handler.post(progressRunnable);

            mediaPlayer.setOnCompletionListener(mp -> {
                handler.removeCallbacks(progressRunnable);
                if (currentListener != null) {
                    currentListener.onPlaybackCompleted(currentMessageId);
                    currentListener.onPlaybackStateChanged(currentMessageId, false);
                }
                stop();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                stop();
                return true;
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio playback: " + e.getMessage(), e);
            stop();
        }
    }

    public synchronized void stop() {
        handler.removeCallbacks(progressRunnable);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }

        if (currentListener != null && currentMessageId != -1) {
            currentListener.onPlaybackStateChanged(currentMessageId, false);
        }

        currentMessageId = -1;
        currentFilePath = null;
        currentListener = null;
    }

    public synchronized void seekTo(int progressMs) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(progressMs);
        }
    }

    public synchronized boolean isPlaying(long messageId) {
        return currentMessageId == messageId && mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public synchronized long getCurrentPlayingMessageId() {
        return (mediaPlayer != null && mediaPlayer.isPlaying()) ? currentMessageId : -1;
    }
}
