package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.recyclerview.widget.RecyclerView;
import com.nexgram.messenger.R;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;
import com.nexgram.messenger.ui.helpers.TelegramDateFormatter;
import com.nexgram.messenger.ui.helpers.TelegramMessageFormatter;
import java.util.List;
import org.drinkless.tdlib.TdApi;

/**
 * Native Telegram Chat Adapter
 * Renders real Telegram chats matching official Telegram Android design and dimensions:
 * - 54dp circular avatar with official Telegram palette (7 colors) & monogram initials / photos
 * - Title, mute indicator, checkmark status (outgoing sent/read), and timestamp
 * - Last message preview formatted by media type (text, photo, voice, video, doc, sticker)
 * - Pinned pushpin indicator and unread counter badges (blue unmuted, gray muted)
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    public interface OnChatClickListener {
        void onChatClick(TdApi.Chat chat);
    }

    private final Context context;
    private final List<TdApi.Chat> items;
    private final OnChatClickListener listener;
    private TdLibAccountClient client;

    public ChatAdapter(Context context, List<TdApi.Chat> items, TdLibAccountClient client, OnChatClickListener listener) {
        this.context = context;
        this.items = items;
        this.client = client;
        this.listener = listener;
    }

    public void setActiveClient(TdLibAccountClient client) {
        this.client = client;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        TdApi.Chat chat = items.get(position);
        holder.bind(chat, context, client, listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAvatarCircle;
        private final TextView tvAvatarInitials;
        private final ImageView ivAvatarPhoto;
        private final TextView tvChatTitle;
        private final ImageView ivChatMuted;
        private final ImageView ivOutgoingStatus;
        private final TextView tvChatTime;
        private final TextView tvLastMessage;
        private final ImageView ivChatPinned;
        private final TextView tvUnreadBadge;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatarCircle = itemView.findViewById(R.id.ivAvatarCircle);
            tvAvatarInitials = itemView.findViewById(R.id.tvAvatarInitials);
            ivAvatarPhoto = itemView.findViewById(R.id.ivAvatarPhoto);
            tvChatTitle = itemView.findViewById(R.id.tvChatTitle);
            ivChatMuted = itemView.findViewById(R.id.ivChatMuted);
            ivOutgoingStatus = itemView.findViewById(R.id.ivOutgoingStatus);
            tvChatTime = itemView.findViewById(R.id.tvChatTime);
            tvLastMessage = itemView.findViewById(R.id.tvLastMessage);
            ivChatPinned = itemView.findViewById(R.id.ivChatPinned);
            tvUnreadBadge = itemView.findViewById(R.id.tvUnreadBadge);
        }

        public void bind(TdApi.Chat chat, Context context, TdLibAccountClient client, OnChatClickListener listener) {
            if (chat == null) return;

            // 1. Title
            String title = chat.title != null && !chat.title.isEmpty() ? chat.title : "Telegram Chat";
            tvChatTitle.setText(title);

            // 2. Avatar monogram & color palette
            int bgColor = TelegramAvatarHelper.getAvatarColor(chat.id);
            ivAvatarCircle.setColorFilter(bgColor);
            String initials = TelegramAvatarHelper.getInitials(title);
            tvAvatarInitials.setText(initials);

            // Check if local circular avatar bitmap is available
            boolean photoLoaded = false;
            if (chat.photo != null && chat.photo.small != null) {
                String localPath = chat.photo.small.local != null ? chat.photo.small.local.path : null;
                if (localPath != null && !localPath.isEmpty()) {
                    RoundedBitmapDrawable round = TelegramAvatarHelper.loadCircularBitmap(context, localPath);
                    if (round != null) {
                        ivAvatarPhoto.setImageDrawable(round);
                        ivAvatarPhoto.setVisibility(View.VISIBLE);
                        tvAvatarInitials.setVisibility(View.GONE);
                        photoLoaded = true;
                    }
                }
                // Request automatic background download if not yet loaded
                if (!photoLoaded && client != null && chat.photo.small.local != null &&
                    chat.photo.small.local.canBeDownloaded && !chat.photo.small.local.isDownloadingActive) {
                    client.downloadFile(chat.photo.small.id, 1, null);
                }
            }

            if (!photoLoaded) {
                ivAvatarPhoto.setVisibility(View.GONE);
                tvAvatarInitials.setVisibility(View.VISIBLE);
            }

            // 3. Last Message Preview
            String preview = TelegramMessageFormatter.formatMessagePreview(chat.lastMessage);
            tvLastMessage.setText(preview);

            // 4. Timestamp
            if (chat.lastMessage != null && chat.lastMessage.date > 0) {
                tvChatTime.setText(TelegramDateFormatter.formatChatTimestamp(chat.lastMessage.date));
                tvChatTime.setVisibility(View.VISIBLE);
            } else {
                tvChatTime.setText("");
                tvChatTime.setVisibility(View.GONE);
            }

            // 5. Outgoing status checkmarks
            if (chat.lastMessage != null && chat.lastMessage.isOutgoing) {
                ivOutgoingStatus.setVisibility(View.VISIBLE);
                if (chat.lastMessage.id <= chat.lastReadOutboxMessageId) {
                    ivOutgoingStatus.setImageResource(R.drawable.ic_check_double);
                } else {
                    ivOutgoingStatus.setImageResource(R.drawable.ic_check_single);
                }
            } else {
                ivOutgoingStatus.setVisibility(View.GONE);
            }

            // 6. Muted state
            boolean isMuted = chat.notificationSettings != null && chat.notificationSettings.muteFor > 0;
            ivChatMuted.setVisibility(isMuted ? View.VISIBLE : View.GONE);

            // 7. Pinned state
            boolean isPinned = false;
            if (chat.positions != null) {
                for (TdApi.ChatPosition pos : chat.positions) {
                    if (pos.list instanceof TdApi.ChatListMain && pos.isPinned) {
                        isPinned = true;
                        break;
                    }
                }
            }
            ivChatPinned.setVisibility(isPinned ? View.VISIBLE : View.GONE);

            // 8. Unread badge
            if (chat.unreadCount > 0) {
                tvUnreadBadge.setText(String.valueOf(chat.unreadCount));
                tvUnreadBadge.setBackgroundResource(isMuted ? R.drawable.bg_unread_badge_muted : R.drawable.bg_unread_badge);
                tvUnreadBadge.setVisibility(View.VISIBLE);
            } else if (chat.isMarkedAsUnread) {
                tvUnreadBadge.setText("");
                tvUnreadBadge.setBackgroundResource(isMuted ? R.drawable.bg_unread_badge_muted : R.drawable.bg_unread_badge);
                tvUnreadBadge.setVisibility(View.VISIBLE);
            } else {
                tvUnreadBadge.setVisibility(View.GONE);
            }

            // 9. OnClick Listener
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onChatClick(chat);
                }
            });
        }
    }
}
