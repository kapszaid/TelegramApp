package com.nexgram.messenger.ui.helpers;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Formats Telegram timestamps matching official Telegram Android behavior:
 * - Today: "10:45 AM"
 * - Yesterday: "Yesterday"
 * - Within 7 days: "Mon", "Tue", etc.
 * - Same year: "Nov 12"
 * - Different year: "12.11.23"
 */
public class TelegramDateFormatter {

    public static String formatChatTimestamp(long dateSeconds) {
        if (dateSeconds <= 0) return "";

        long dateMillis = dateSeconds * 1000L;
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(dateMillis);

        Calendar nowCal = Calendar.getInstance();

        // Check if today
        if (nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
            SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
            return timeFormat.format(new Date(dateMillis));
        }

        // Check if yesterday
        Calendar yesterdayCal = Calendar.getInstance();
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1);
        if (yesterdayCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            yesterdayCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }

        // Check if within past 6 days
        long diffDays = (nowCal.getTimeInMillis() - dateMillis) / (24 * 60 * 60 * 1000L);
        if (diffDays < 7 && diffDays > 0) {
            SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.getDefault());
            return dayFormat.format(new Date(dateMillis));
        }

        // Check if same year
        if (nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)) {
            SimpleDateFormat monthFormat = new SimpleDateFormat("MMM d", Locale.getDefault());
            return monthFormat.format(new Date(dateMillis));
        }

        // Different year
        SimpleDateFormat yearFormat = new SimpleDateFormat("dd.MM.yy", Locale.getDefault());
        return yearFormat.format(new Date(dateMillis));
    }
}
