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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.drinkless.tdlib.TdApi;

/**
 * Modern Telegram Message Adapter
 * Renders authentic Telegram conversation bubbles matching the official Telegram Android app:
 * - Incoming bubble (#182533) left-aligned with sender name and timestamp
 * - Outgoing bubble (#2B5278) right-aligned with timestamp and read/sent checkmarks
 * - Seamless transition from pending (clock icon) to sent (single check) / read (double check)
 * - Strict deduplication preventing duplicate message bubbles
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

    public synchronized void setMessages(List<TdApi.Message> rawMessages) {
        items.clear();
        if (rawMessages == null || rawMessages.isEmpty()) {
            safeNotifyDataSetChanged();
            return;
        }

        List<TdApi.Message> chronological = new ArrayList<>(rawMessages);
        if (chronological.size() > 1 && chronological.get(0).date > chronological.get(chronological.size() - 1).date) {
            java.util.Collections.reverse(chronological);
        }

        // Deduplicate messages by their unique 64-bit TDLib ID
        LinkedHashMap<Long, TdApi.Message> uniqueMessages = new LinkedHashMap<>();
        for (TdApi.Message msg : chronological) {
            if (msg != null) {
                uniqueMessages.put(msg.id, msg);
            }
        }

        int lastDayOfYear = -1;
        int lastYear = -1;

        for (TdApi.Message msg : uniqueMessages.values()) {
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

        safeNotifyDataSetChanged();
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
        // If not found (e.g. sent before activity opened), append cleanly
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
