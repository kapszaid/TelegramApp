package com.nexgram.messenger.ui.helpers;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Telegram Media Helper
 *
 * Provides utilities for:
 * - Content URI reading and safe cache copy for TDLib C++ ingestion
 * - Dimension and duration extraction (images & videos)
 * - Safe sampled bitmap loading
 * - Human-readable size & duration formatting
 * - FileProvider launching for downloaded documents
 */
public class TelegramMediaHelper {
    private static final String TAG = "TelegramMediaHelper";

    public static class MediaDimensions {
        public final int width;
        public final int height;
        public final int duration; // in seconds, 0 for images

        public MediaDimensions(int width, int height, int duration) {
            this.width = width;
            this.height = height;
            this.duration = duration;
        }
    }

    public static class AudioMetadata {
        public final int duration;
        public final String title;
        public final String performer;

        public AudioMetadata(int duration, String title, String performer) {
            this.duration = duration;
            this.title = title;
            this.performer = performer;
        }
    }

    public static AudioMetadata getAudioMetadata(String filePath) {
        if (filePath == null) return new AudioMetadata(0, "Audio", "Unknown Artist");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);

            int durationMs = durStr != null ? Integer.parseInt(durStr) : 0;
            int durationSec = Math.max(1, durationMs / 1000);
            if (title == null || title.trim().isEmpty()) {
                File f = new File(filePath);
                title = f.getName();
            }
            if (artist == null || artist.trim().isEmpty()) {
                artist = "Unknown Artist";
            }
            return new AudioMetadata(durationSec, title, artist);
        } catch (Exception e) {
            File f = new File(filePath);
            return new AudioMetadata(0, f.getName(), "Unknown Artist");
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    public static File copyUriToCache(Context context, Uri uri, String preferredName) {
        if (context == null || uri == null) return null;

        String name = preferredName != null && !preferredName.trim().isEmpty()
            ? preferredName
            : getFileName(context, uri);

        if (name == null || name.trim().isEmpty()) {
            name = "file_" + System.currentTimeMillis();
        }

        // Sanitize filename
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

        File outDir = new File(context.getCacheDir(), "media_out");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        File outFile = new File(outDir, System.currentTimeMillis() + "_" + name);

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) return null;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return outFile;
        } catch (Exception e) {
            Log.e(TAG, "Error copying URI to cache: " + e.getMessage());
            return null;
        }
    }

    public static String getFileName(Context context, Uri uri) {
        if (context == null || uri == null) return "file";
        String name = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (name == null && uri.getPath() != null) {
            int cut = uri.getPath().lastIndexOf('/');
            if (cut != -1) {
                name = uri.getPath().substring(cut + 1);
            }
        }
        return name != null ? name : "file";
    }

    public static MediaDimensions getImageDimensions(String filePath) {
        if (filePath == null) return new MediaDimensions(800, 600, 0);
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);
            int width = options.outWidth > 0 ? options.outWidth : 800;
            int height = options.outHeight > 0 ? options.outHeight : 600;
            return new MediaDimensions(width, height, 0);
        } catch (Exception e) {
            return new MediaDimensions(800, 600, 0);
        }
    }

    public static MediaDimensions getVideoMetadata(String filePath) {
        if (filePath == null) return new MediaDimensions(640, 360, 0);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            int durationMs = durStr != null ? Integer.parseInt(durStr) : 0;
            int durationSec = Math.max(1, durationMs / 1000);
            int width = widthStr != null ? Integer.parseInt(widthStr) : 640;
            int height = heightStr != null ? Integer.parseInt(heightStr) : 360;

            return new MediaDimensions(width, height, durationSec);
        } catch (Exception e) {
            return new MediaDimensions(640, 360, 10);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {}
        }
    }

    public static Bitmap decodeSampledBitmap(String filePath, int reqWidth, int reqHeight) {
        if (filePath == null || !new File(filePath).exists()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(filePath, options);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String formatDuration(int seconds) {
        if (seconds <= 0) return "0:00";
        int mins = seconds / 60;
        int secs = seconds % 60;
        if (mins < 60) {
            return String.format(Locale.getDefault(), "%d:%02d", mins, secs);
        }
        int hours = mins / 60;
        mins = mins % 60;
        return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, mins, secs);
    }

    public static void openFileWithSystem(Context context, String filePath, String mimeType) {
        if (context == null || filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            Uri contentUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".provider",
                file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            String type = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "*/*";
            intent.setDataAndType(contentUri, type);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open file: " + e.getMessage());
        }
    }
}
