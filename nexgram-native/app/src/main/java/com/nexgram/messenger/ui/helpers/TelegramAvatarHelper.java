package com.nexgram.messenger.ui.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import java.io.File;

/**
 * Telegram Avatar Helper
 * Matches official Telegram Android avatar colors and monogram extraction:
 * 7 official Telegram gradient colors selected deterministically by ID modulo 7.
 */
public class TelegramAvatarHelper {

    public static final int[] AVATAR_COLORS = {
        0xFFE17076, // 0: Red
        0xFFFAA774, // 1: Orange
        0xFFA695E7, // 2: Violet
        0xFF7BC862, // 3: Green
        0xFF6EC9CB, // 4: Cyan
        0xFF65AADD, // 5: Blue
        0xFFEE7AAE  // 6: Pink
    };

    public static int getAvatarColor(long id) {
        int index = Math.abs((int) (id % AVATAR_COLORS.length));
        return AVATAR_COLORS[index];
    }

    public static String getInitials(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "TG";
        }
        String clean = title.trim();
        String[] parts = clean.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        } else if (!clean.isEmpty()) {
            return clean.substring(0, Math.min(clean.length(), 2)).toUpperCase();
        }
        return "TG";
    }

    public static RoundedBitmapDrawable loadCircularBitmap(Context context, String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;
        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) return null;

        try {
            Bitmap bmp = BitmapFactory.decodeFile(filePath);
            if (bmp != null) {
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(context.getResources(), bmp);
                drawable.setCircular(true);
                return drawable;
            }
        } catch (Throwable t) {
            // Ignore decoding failure, fall back to initials monogram
        }
        return null;
    }
}
