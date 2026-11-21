package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nexgram.messenger.R;
import com.nexgram.messenger.ui.helpers.TelegramDateFormatter;
import com.nexgram.messenger.ui.helpers.TelegramMessageFormatter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.drinkless.tdlib.TdApi;

/**
 * Modern Telegram Message Adapter
 *
 * Implements official Telegram conversation list with pagination, real-time updates,
 * editing, deletion, read status, and strict deduplication:
 * - Chronological message list (oldest at top, newest at bottom)
 * - Automatic date header pill insertion on calendar day boundaries
 * - Infinite upward scroll pagination support via prependOlderMessages
 * - Dynamic status tick updates (pending clock -> single check -> double check)
 * - Message content update (edited messages) and deletion handling
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_DATE_HEADER = 0;
    private static final int TYPE_INCOMING = 1;
    private static final int TYPE_OUTGOING = 2;

    public interface Item {
        int getType();
    }

    public static class DateHeaderItem implements Item {
        public final String dateText;
        public DateHeaderItem(String dateText) {
            this.dateText = dateText;
        }
        @Override
        public int getType() {
            return TYPE_DATE_HEADER;
        }
    }

    public static class MessageItem implements Item {
        public final TdApi.Message message;
        public MessageItem(TdApi.Message message) {
            this.message = message;
        }
        @Override
        public int getType() {
            return message.isOutgoing ? TYPE_OUTGOING : TYPE_INCOMING;
        }
    }

    private final Context context;
    private final List<Item> items = new ArrayList<>();
    private long lastReadOutboxMessageId = 0;
    private boolean isGroupChat = false;

    public MessageAdapter(Context context) {
        this.context = context;
    }

    public void setGroupChat(boolean isGroupChat) {
        this.isGroupChat = isGroupChat;
    }

    public synchronized void setLastReadOutboxMessageId(long lastReadOutboxMessageId) {
        this.lastReadOutboxMessageId = lastReadOutboxMessageId;
        safeNotifyDataSetChanged();
    }

    /**
     * Initializes adapter with the initial batch of messages (e.g. latest 50).
     */
    public synchronized void setMessages(List<TdApi.Message> rawMessages) {
        items.clear();
        if (rawMessages == null || rawMessages.isEmpty()) {
            safeNotifyDataSetChanged();
            return;
        }

        LinkedHashMap<Long, TdApi.Message> uniqueMessages = new LinkedHashMap<>();
        for (TdApi.Message msg : rawMessages) {
            if (msg != null) {
                uniqueMessages.put(msg.id, msg);
            }
        }

        List<TdApi.Message> sorted = new ArrayList<>(uniqueMessages.values());
        Collections.sort(sorted, (m1, m2) -> {
            if (m1.date != m2.date) {
                return Integer.compare(m1.date, m2.date);
            }
            return Long.compare(m1.id, m2.id);
        });

        rebuildItems(sorted);
        safeNotifyDataSetChanged();
    }

    /**
     * Prepends older historical messages retrieved via pagination.
     * Merges with existing messages, maintains strict chronological order,
     * and returns the net number of items added before the previous top item.
     */
    public synchronized int prependOlderMessages(List<TdApi.Message> olderMessages) {
        if (olderMessages == null || olderMessages.isEmpty()) {
            return 0;
        }

        LinkedHashMap<Long, TdApi.Message> allMap = new LinkedHashMap<>();

        // Add older messages
        for (TdApi.Message m : olderMessages) {
            if (m != null) {
                allMap.put(m.id, m);
            }
        }

        // Add existing messages
        for (Item it : items) {
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                allMap.put(mi.message.id, mi.message);
            }
        }

        int previousCount = items.size();

        List<TdApi.Message> sortedAll = new ArrayList<>(allMap.values());
        Collections.sort(sortedAll, (m1, m2) -> {
            if (m1.date != m2.date) {
                return Integer.compare(m1.date, m2.date);
            }
            return Long.compare(m1.id, m2.id);
        });

        rebuildItems(sortedAll);
        safeNotifyDataSetChanged();

        return Math.max(0, items.size() - previousCount);
    }

    public synchronized void appendMessage(TdApi.Message msg) {
        appendOrUpdateMessage(msg);
    }

    /**
     * Appends a new message or updates an existing message with matching ID.
     * Prevents duplicate bubbles when TDLib sends UpdateNewMessage or SendMessage result.
     */
    public synchronized void appendOrUpdateMessage(TdApi.Message msg) {
        if (msg == null) return;

        // Check if message already exists by ID
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (mi.message.id == msg.id) {
                    items.set(i, new MessageItem(msg));
                    safeNotifyItemChanged(i);
                    return;
                }
            }
        }

        // Add date header if crossing a calendar day boundary
        if (items.isEmpty()) {
            items.add(new DateHeaderItem(formatDateHeader(msg.date * 1000L)));
        } else {
            Item last = items.get(items.size() - 1);
            if (last instanceof MessageItem) {
                TdApi.Message prev = ((MessageItem) last).message;
                Calendar prevCal = Calendar.getInstance();
                prevCal.setTimeInMillis(prev.date * 1000L);
                Calendar curCal = Calendar.getInstance();
                curCal.setTimeInMillis(msg.date * 1000L);
                if (prevCal.get(Calendar.DAY_OF_YEAR) != curCal.get(Calendar.DAY_OF_YEAR) ||
                    prevCal.get(Calendar.YEAR) != curCal.get(Calendar.YEAR)) {
                    items.add(new DateHeaderItem(formatDateHeader(msg.date * 1000L)));
                }
            }
        }

        items.add(new MessageItem(msg));
        safeNotifyItemInserted(items.size() - 1);
    }

    /**
     * Handles TDLib UpdateMessageSendSucceeded event.
     * Replaces pending message (oldMessageId) with final server message (sendingState == null),
     * instantly updating the clock pending icon to a single or double delivery checkmark.
     */
    public synchronized void onMessageSendSucceeded(long oldMessageId, TdApi.Message newMessage) {
        if (newMessage == null) return;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (mi.message.id == oldMessageId || mi.message.id == newMessage.id) {
                    items.set(i, new MessageItem(newMessage));
                    safeNotifyItemChanged(i);
                    return;
                }
            }
        }
        // If not found, append cleanly
        appendOrUpdateMessage(newMessage);
    }

    /**
     * Handles TDLib UpdateMessageSendFailed event.
     */
    public synchronized void onMessageSendFailed(long oldMessageId, TdApi.Message failedMessage) {
        if (failedMessage == null) return;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (mi.message.id == oldMessageId || mi.message.id == failedMessage.id) {
                    items.set(i, new MessageItem(failedMessage));
                    safeNotifyItemChanged(i);
                    return;
                }
            }
        }
    }

    /**
     * Handles TDLib UpdateMessageContent (e.g. message text edited).
     */
    public synchronized void onMessageContentUpdated(long messageId, TdApi.MessageContent newContent) {
        if (newContent == null) return;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (mi.message.id == messageId) {
                    mi.message.content = newContent;
                    safeNotifyItemChanged(i);
                    return;
                }
            }
        }
    }

    /**
     * Handles TDLib UpdateMessageEdited (edit date updated).
     */
    public synchronized void onMessageEdited(long messageId, int editDate) {
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (mi.message.id == messageId) {
                    mi.message.editDate = editDate;
                    safeNotifyItemChanged(i);
                    return;
                }
            }
        }
    }

    /**
     * Handles TDLib UpdateDeleteMessages. Removes matching messages from the list
     * and clears any empty date headers.
     */
    public synchronized void onMessagesDeleted(long[] messageIds) {
        if (messageIds == null || messageIds.length == 0) return;

        Set<Long> toDelete = new HashSet<>();
        for (long id : messageIds) {
            toDelete.add(id);
        }

        boolean changed = false;
        List<TdApi.Message> remaining = new ArrayList<>();
        for (Item it : items) {
            if (it instanceof MessageItem) {
                MessageItem mi = (MessageItem) it;
                if (!toDelete.contains(mi.message.id)) {
                    remaining.add(mi.message);
                } else {
                    changed = true;
                }
            }
        }

        if (changed) {
            setMessages(remaining);
        }
    }

    /**
     * Returns true if a message with the given TDLib 64-bit ID exists in the adapter.
     */
    public synchronized boolean containsMessage(long messageId) {
        for (Item it : items) {
            if (it instanceof MessageItem && ((MessageItem) it).message.id == messageId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the ID of the oldest message in the adapter (first MessageItem), or 0 if none.
     */
    public synchronized long getOldestMessageId() {
        for (Item it : items) {
            if (it instanceof MessageItem) {
                return ((MessageItem) it).message.id;
            }
        }
        return 0L;
    }

    /**
     * Returns the ID of the newest message in the adapter (last MessageItem), or 0 if none.
     */
    public synchronized long getNewestMessageId() {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it instanceof MessageItem) {
                return ((MessageItem) it).message.id;
            }
        }
        return 0L;
    }

    /**
     * Returns total count of message bubbles (excluding date headers).
     */
    public synchronized int getMessageCount() {
        int count = 0;
        for (Item it : items) {
            if (it instanceof MessageItem) count++;
        }
        return count;
    }

    private void rebuildItems(List<TdApi.Message> sortedChronological) {
        items.clear();
        if (sortedChronological == null || sortedChronological.isEmpty()) {
            return;
        }

        int lastDayOfYear = -1;
        int lastYear = -1;

        for (TdApi.Message msg : sortedChronological) {
            if (msg.date > 0) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(msg.date * 1000L);
                int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
                int year = cal.get(Calendar.YEAR);

                if (dayOfYear != lastDayOfYear || year != lastYear) {
                    items.add(new DateHeaderItem(formatDateHeader(msg.date * 1000L)));
                    lastDayOfYear = dayOfYear;
                    lastYear = year;
                }
            }
            items.add(new MessageItem(msg));
        }
    }

    private void safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {}
    }

    private void safeNotifyItemChanged(int position) {
        try {
            notifyItemChanged(position);
        } catch (Throwable ignored) {}
    }

    private void safeNotifyItemInserted(int position) {
        try {
            notifyItemInserted(position);
        } catch (Throwable ignored) {}
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getType();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DATE_HEADER) {
            View v = inflater.inflate(R.layout.item_message_date, parent, false);
            return new DateHeaderViewHolder(v);
        } else if (viewType == TYPE_OUTGOING) {
            View v = inflater.inflate(R.layout.item_message_outgoing, parent, false);
            return new OutgoingViewHolder(v);
        } else {
            View v = inflater.inflate(R.layout.item_message_incoming, parent, false);
            return new IncomingViewHolder(v);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof DateHeaderViewHolder) {
            ((DateHeaderViewHolder) holder).tvDateHeader.setText(((DateHeaderItem) item).dateText);
        } else if (holder instanceof OutgoingViewHolder) {
            bindOutgoing((OutgoingViewHolder) holder, ((MessageItem) item).message);
        } else if (holder instanceof IncomingViewHolder) {
            bindIncoming((IncomingViewHolder) holder, ((MessageItem) item).message);
        }
    }

    private void bindOutgoing(OutgoingViewHolder holder, TdApi.Message message) {
        holder.tvMessageText.setText(getMessageContentText(message));
        holder.tvMessageTime.setText(formatTime(message.date));

        // Delivery status tick
        if (message.sendingState != null) {
            // Pending / Sending: Clock icon
            holder.ivDeliveryStatus.setImageResource(R.drawable.ic_clock_pending);
            holder.ivDeliveryStatus.setColorFilter(0xFF82A7C9, PorterDuff.Mode.SRC_IN);
        } else if (message.id <= lastReadOutboxMessageId && lastReadOutboxMessageId > 0) {
            // Read: Double checkmark
            holder.ivDeliveryStatus.setImageResource(R.drawable.ic_check_double);
            holder.ivDeliveryStatus.setColorFilter(0xFF82A7C9, PorterDuff.Mode.SRC_IN);
        } else {
            // Sent: Single checkmark
            holder.ivDeliveryStatus.setImageResource(R.drawable.ic_check_single);
            holder.ivDeliveryStatus.setColorFilter(0xFF82A7C9, PorterDuff.Mode.SRC_IN);
        }
    }

    private void bindIncoming(IncomingViewHolder holder, TdApi.Message message) {
        holder.tvMessageText.setText(getMessageContentText(message));
        holder.tvMessageTime.setText(formatTime(message.date));

        if (isGroupChat && message.senderId != null) {
            holder.tvSenderName.setVisibility(View.VISIBLE);
            holder.tvSenderName.setText(getSenderDisplayName(message.senderId));
        } else {
            holder.tvSenderName.setVisibility(View.GONE);
        }
    }

    private String getMessageContentText(TdApi.Message message) {
        if (message == null || message.content == null) return "";
        if (message.content instanceof TdApi.MessageText) {
            TdApi.MessageText textMsg = (TdApi.MessageText) message.content;
            if (textMsg.text != null && textMsg.text.text != null) {
                return textMsg.text.text;
            }
        }
        return TelegramMessageFormatter.formatMessagePreview(message);
    }

    private String formatTime(long dateSeconds) {
        if (dateSeconds <= 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.getDefault());
        return format.format(new Date(dateSeconds * 1000L));
    }

    private String formatDateHeader(long dateMillis) {
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(dateMillis);
        Calendar nowCal = Calendar.getInstance();

        if (nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }

        Calendar yestCal = Calendar.getInstance();
        yestCal.add(Calendar.DAY_OF_YEAR, -1);
        if (yestCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            yestCal.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }

        if (nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM d", Locale.getDefault());
            return sdf.format(new Date(dateMillis));
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        return sdf.format(new Date(dateMillis));
    }

    private String getSenderDisplayName(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser) {
            return "User " + ((TdApi.MessageSenderUser) sender).userId;
        } else if (sender instanceof TdApi.MessageSenderChat) {
            return "Chat " + ((TdApi.MessageSenderChat) sender).chatId;
        }
        return "Member";
    }

    static class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvDateHeader;
        DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDateHeader = itemView.findViewById(R.id.tvDateHeader);
        }
    }

    static class OutgoingViewHolder extends RecyclerView.ViewHolder {
        final TextView tvMessageText;
        final TextView tvMessageTime;
        final ImageView ivDeliveryStatus;
        OutgoingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessageText = itemView.findViewById(R.id.tvMessageText);
            tvMessageTime = itemView.findViewById(R.id.tvMessageTime);
            ivDeliveryStatus = itemView.findViewById(R.id.ivDeliveryStatus);
        }
    }

    static class IncomingViewHolder extends RecyclerView.ViewHolder {
        final TextView tvSenderName;
        final TextView tvMessageText;
        final TextView tvMessageTime;
        IncomingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSenderName = itemView.findViewById(R.id.tvSenderName);
            tvMessageText = itemView.findViewById(R.id.tvMessageText);
            tvMessageTime = itemView.findViewById(R.id.tvMessageTime);
        }
    }
}
