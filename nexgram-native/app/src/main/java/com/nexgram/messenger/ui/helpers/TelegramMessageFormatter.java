package com.nexgram.messenger.ui.helpers;

import org.drinkless.tdlib.TdApi;

/**
 * Extracts and formats human-readable Telegram preview strings from real TdApi.Message objects.
 */
public class TelegramMessageFormatter {

    public static String formatMessagePreview(TdApi.Message message) {
        if (message == null || message.content == null) {
            return "";
        }

        TdApi.MessageContent content = message.content;

        if (content instanceof TdApi.MessageText) {
            TdApi.MessageText textMsg = (TdApi.MessageText) content;
            return textMsg.text != null && textMsg.text.text != null ? textMsg.text.text.replace("\n", " ") : "";
        } else if (content instanceof TdApi.MessagePhoto) {
            TdApi.MessagePhoto photoMsg = (TdApi.MessagePhoto) content;
            if (photoMsg.caption != null && photoMsg.caption.text != null && !photoMsg.caption.text.trim().isEmpty()) {
                return "📷 " + photoMsg.caption.text.replace("\n", " ");
            }
            return "📷 Photo";
        } else if (content instanceof TdApi.MessageVideo) {
            TdApi.MessageVideo videoMsg = (TdApi.MessageVideo) content;
            if (videoMsg.caption != null && videoMsg.caption.text != null && !videoMsg.caption.text.trim().isEmpty()) {
                return "📹 " + videoMsg.caption.text.replace("\n", " ");
            }
            return "📹 Video";
        } else if (content instanceof TdApi.MessageVoiceNote) {
            return "🎤 Voice message";
        } else if (content instanceof TdApi.MessageAudio) {
            return "🎵 Audio";
        } else if (content instanceof TdApi.MessageDocument) {
            TdApi.MessageDocument doc = (TdApi.MessageDocument) content;
            if (doc.document != null && doc.document.fileName != null && !doc.document.fileName.isEmpty()) {
                return "📄 " + doc.document.fileName;
            }
            return "📄 Document";
        } else if (content instanceof TdApi.MessageSticker) {
            TdApi.MessageSticker sticker = (TdApi.MessageSticker) content;
            String emoji = sticker.sticker != null && sticker.sticker.emoji != null ? sticker.sticker.emoji : "";
            return (emoji + " Sticker").trim();
        } else if (content instanceof TdApi.MessageAnimation) {
            return "GIF";
        } else if (content instanceof TdApi.MessageLocation) {
            return "📍 Location";
        } else if (content instanceof TdApi.MessageContact) {
            return "👤 Contact";
        } else if (content instanceof TdApi.MessageCall) {
            return "📞 Call";
        } else if (content instanceof TdApi.MessagePinMessage) {
            return "📌 Pinned message";
        } else if (content instanceof TdApi.MessageChatAddMembers) {
            return "Added member(s)";
        } else if (content instanceof TdApi.MessageChatDeleteMember) {
            return "Left the group";
        } else if (content instanceof TdApi.MessageChatJoinByLink) {
            return "Joined via invite link";
        }

        return "Message";
    }
}
