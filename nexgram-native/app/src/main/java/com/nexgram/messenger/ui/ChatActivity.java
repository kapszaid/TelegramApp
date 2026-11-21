package com.nexgram.messenger.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import androidx.core.content.ContextCompat;
import com.nexgram.messenger.ui.helpers.TelegramAudioPlayer;
import com.nexgram.messenger.ui.helpers.TelegramVoiceRecorder;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.MessageAdapter;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;
import com.nexgram.messenger.ui.helpers.TelegramDateFormatter;
import com.nexgram.messenger.ui.helpers.TelegramMediaHelper;
import com.nexgram.messenger.ui.helpers.TelegramMessageFormatter;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.drinkless.tdlib.TdApi;

/**
 * Modern Telegram Conversation Screen
 *
 * Implements Milestone 2.6 & Milestone 2.7 — Complete Telegram Message Actions:
 * - Real Telegram message history loading via TDLib GetChatHistory
 * - Infinite upward scroll pagination with seamless scroll-offset preservation
 * - Real-time updates: UpdateNewMessage, SendSucceeded, SendFailed, ContentEdited, MessageDeleted, ReadStatus
 * - Reply to message with preview header and in-bubble navigation
 * - Edit own message with in-composer editing and real-time update
 * - Forward message(s) with Telegram chat selection interface
 * - Delete message(s) with "delete for me" and "delete for everyone" scope
 * - Android ClipboardManager text copying
 * - Pin and unpin message functionality with top pinned message banner
 * - Multi-message selection mode with dedicated action toolbar
 * - Edge-to-edge window insets handling for system bars and soft keyboard (adjustResize)
 * - Zero fake mock data: 100% genuine TDLib MTProto engine
 */
public class ChatActivity extends AppCompatActivity implements MessageAdapter.OnMessageInteractionListener {
    private static final String TAG = "ChatActivity";
    private static final int PAGE_LIMIT = 50;

    private long chatId;
    private String chatTitle;
    private int avatarFileId = -1;

    // Normal Toolbar
    private Toolbar chatToolbar;
    private ImageView btnBack;
    private ImageView ivChatAvatarCircle;
    private TextView tvChatAvatarInitials;
    private ImageView ivChatAvatarPhoto;
    private TextView tvChatTitle;
    private TextView tvChatSubtitle;

    // Selection Toolbar
    private Toolbar selectionToolbar;
    private ImageView btnCancelSelection;
    private TextView tvSelectionCount;
    private ImageView btnSelectionCopy;
    private ImageView btnSelectionForward;
    private ImageView btnSelectionDelete;

    // Pinned Message Bar
    private LinearLayout layoutPinnedBar;
    private TextView tvPinnedTitle;
    private TextView tvPinnedSnippet;
    private ImageView btnPinnedUnpin;

    // Message List
    private RecyclerView recyclerViewMessages;
    private ProgressBar pbLoadingMessages;
    private ProgressBar pbLoadingOlderMessages;
    private TextView tvEmptyMessages;

    // Composer Header (Reply or Edit mode)
    private LinearLayout layoutComposerHeader;
    private ImageView ivComposerHeaderIcon;
    private TextView tvComposerHeaderTitle;
    private TextView tvComposerHeaderSubtitle;
    private ImageView btnCancelComposerAction;

    // Bottom Input Bar
    private LinearLayout layoutBottomInput;
    private ImageView btnEmoji;
    private EditText etMessageInput;
    private ImageView btnAttach;
    private FrameLayout btnSendOrMic;
    private ImageView ivSendBgCircle;
    private ImageView ivSendOrMicIcon;

    // Live Voice Recording Views
    private LinearLayout layoutVoiceRecording;
    private ImageView ivRecordDot;
    private TextView tvRecordDuration;
    private TextView tvSlideToCancel;
    private float voiceTouchStartX = 0f;
    private boolean isVoiceRecordingCancelled = false;

    private MessageAdapter messageAdapter;
    private LinearLayoutManager layoutManager;
    private TdLibAccountClient activeClient;

    // Pagination state
    private boolean isLoadingOlderHistory = false;
    private boolean hasReachedBeginningOfHistory = false;

    // Action state
    private TdApi.Message replyingToMessage = null;
    private TdApi.Message editingMessage = null;
    private TdApi.Message currentPinnedMessage = null;

    // Permissions & Media Pickers
    private final ActivityResultLauncher<String> recordAudioPermissionLauncher = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        isGranted -> {
            if (isGranted) {
                Toast.makeText(this, "Microphone permission granted. Press and hold to record.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Microphone permission is required to record voice messages", Toast.LENGTH_SHORT).show();
            }
        }
    );

    private final ActivityResultLauncher<String> pickPhotoLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                handleSelectedMediaUri(uri, "image");
            }
        }
    );

    private final ActivityResultLauncher<String> pickVideoLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                handleSelectedMediaUri(uri, "video");
            }
        }
    );

    private final ActivityResultLauncher<String> pickAudioLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                handleSelectedMediaUri(uri, "audio");
            }
        }
    );

    private final ActivityResultLauncher<String> pickDocumentLauncher = registerForActivityResult(
        new ActivityResultContracts.GetContent(),
        uri -> {
            if (uri != null) {
                handleSelectedMediaUri(uri, "document");
            }
        }
    );

    private final TdLibAccountClient.RawUpdateListener rawUpdateListener = this::handleIncomingTdlibUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge system window inset handling
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_chat);

        chatId = getIntent().getLongExtra("chat_id", 0);
        chatTitle = getIntent().getStringExtra("chat_title");
        if (chatTitle == null || chatTitle.trim().isEmpty()) {
            chatTitle = "Telegram";
        }

        initViews();
        setupWindowInsets();
        setupChatHeader();
        setupSelectionToolbar();
        setupPinnedBar();
        setupComposerHeader();
        setupRecyclerView();
        setupInputBar();
        connectTdLibAndLoadMessages();
    }

    private void initViews() {
        chatToolbar = findViewById(R.id.chatToolbar);
        btnBack = findViewById(R.id.btnBack);
        ivChatAvatarCircle = findViewById(R.id.ivChatAvatarCircle);
        tvChatAvatarInitials = findViewById(R.id.tvChatAvatarInitials);
        ivChatAvatarPhoto = findViewById(R.id.ivChatAvatarPhoto);
        tvChatTitle = findViewById(R.id.tvChatTitle);
        tvChatSubtitle = findViewById(R.id.tvChatSubtitle);

        selectionToolbar = findViewById(R.id.selectionToolbar);
        btnCancelSelection = findViewById(R.id.btnCancelSelection);
        tvSelectionCount = findViewById(R.id.tvSelectionCount);
        btnSelectionCopy = findViewById(R.id.btnSelectionCopy);
        btnSelectionForward = findViewById(R.id.btnSelectionForward);
        btnSelectionDelete = findViewById(R.id.btnSelectionDelete);

        layoutPinnedBar = findViewById(R.id.layoutPinnedBar);
        tvPinnedTitle = findViewById(R.id.tvPinnedTitle);
        tvPinnedSnippet = findViewById(R.id.tvPinnedSnippet);
        btnPinnedUnpin = findViewById(R.id.btnPinnedUnpin);

        recyclerViewMessages = findViewById(R.id.recyclerViewMessages);
        pbLoadingMessages = findViewById(R.id.pbLoadingMessages);
        pbLoadingOlderMessages = findViewById(R.id.pbLoadingOlderMessages);
        tvEmptyMessages = findViewById(R.id.tvEmptyMessages);

        layoutComposerHeader = findViewById(R.id.layoutComposerHeader);
        ivComposerHeaderIcon = findViewById(R.id.ivComposerHeaderIcon);
        tvComposerHeaderTitle = findViewById(R.id.tvComposerHeaderTitle);
        tvComposerHeaderSubtitle = findViewById(R.id.tvComposerHeaderSubtitle);
        btnCancelComposerAction = findViewById(R.id.btnCancelComposerAction);

        layoutBottomInput = findViewById(R.id.layoutBottomInput);
        btnEmoji = findViewById(R.id.btnEmoji);
        etMessageInput = findViewById(R.id.etMessageInput);
        btnAttach = findViewById(R.id.btnAttach);
        btnSendOrMic = findViewById(R.id.btnSendOrMic);
        ivSendBgCircle = findViewById(R.id.ivSendBgCircle);
        ivSendOrMicIcon = findViewById(R.id.ivSendOrMicIcon);

        layoutVoiceRecording = findViewById(R.id.layoutVoiceRecording);
        ivRecordDot = findViewById(R.id.ivRecordDot);
        tvRecordDuration = findViewById(R.id.tvRecordDuration);
        tvSlideToCancel = findViewById(R.id.tvSlideToCancel);

        btnBack.setOnClickListener(v -> onBackPressed());
    }

    private void setupWindowInsets() {
        View root = findViewById(R.id.chatRootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()
            );

            // Pad toolbars to sit cleanly below status bar
            chatToolbar.setPadding(0, insets.top, 0, 0);
            selectionToolbar.setPadding(0, insets.top, 0, 0);

            // Pad bottom input bar above system navigation bar and soft keyboard
            int padLeft = layoutBottomInput.getPaddingLeft();
            int padRight = layoutBottomInput.getPaddingRight();
            int padTop = (int) (6 * getResources().getDisplayMetrics().density);
            int padBottom = insets.bottom + (int) (6 * getResources().getDisplayMetrics().density);

            layoutBottomInput.setPadding(padLeft, padTop, padRight, padBottom);

            return windowInsets;
        });
    }

    private void setupChatHeader() {
        tvChatTitle.setText(chatTitle);

        // Deterministic avatar styling
        int color = TelegramAvatarHelper.getAvatarColor(chatId);
        ivChatAvatarCircle.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        tvChatAvatarInitials.setText(TelegramAvatarHelper.getInitials(chatTitle));

        // Subtitle defaults
        if (chatId == 777000L || "Telegram".equalsIgnoreCase(chatTitle)) {
            tvChatSubtitle.setText("service notifications");
        } else {
            tvChatSubtitle.setText("online");
        }
    }

    private void setupSelectionToolbar() {
        btnCancelSelection.setOnClickListener(v -> messageAdapter.clearSelection());

        btnSelectionCopy.setOnClickListener(v -> {
            List<TdApi.Message> selected = messageAdapter.getSelectedMessages();
            if (!selected.isEmpty()) {
                copyMessageText(selected);
            }
        });

        btnSelectionForward.setOnClickListener(v -> {
            List<Long> ids = new ArrayList<>(messageAdapter.getSelectedMessageIds());
            if (!ids.isEmpty()) {
                showForwardDialog(ids);
            }
        });

        btnSelectionDelete.setOnClickListener(v -> {
            List<Long> ids = new ArrayList<>(messageAdapter.getSelectedMessageIds());
            if (!ids.isEmpty()) {
                showDeleteConfirmationDialog(ids, true);
            }
        });
    }

    private void setupPinnedBar() {
        layoutPinnedBar.setOnClickListener(v -> {
            if (currentPinnedMessage != null) {
                scrollToMessage(currentPinnedMessage.id);
            }
        });

        btnPinnedUnpin.setOnClickListener(v -> {
            if (currentPinnedMessage != null) {
                togglePinMessage(currentPinnedMessage);
            }
        });
    }

    private void setupComposerHeader() {
        btnCancelComposerAction.setOnClickListener(v -> cancelActiveComposerAction());
    }

    private void setupRecyclerView() {
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerViewMessages.setLayoutManager(layoutManager);

        messageAdapter = new MessageAdapter(this);
        messageAdapter.setInteractionListener(this);
        recyclerViewMessages.setAdapter(messageAdapter);

        // Infinite upward scroll pagination listener
        recyclerViewMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (!isLoadingOlderHistory && !hasReachedBeginningOfHistory) {
                    int firstVisible = layoutManager.findFirstVisibleItemPosition();
                    if (firstVisible <= 10 && firstVisible != RecyclerView.NO_POSITION) {
                        loadOlderChatHistory();
                    }
                }
            }
        });
    }

    private void setupInputBar() {
        btnEmoji.setOnClickListener(v -> showStickerGifPicker());

        btnAttach.setOnClickListener(v -> showAttachmentPicker());

        etMessageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                if (hasText || editingMessage != null) {
                    ivSendBgCircle.setVisibility(View.VISIBLE);
                    if (editingMessage != null) {
                        ivSendOrMicIcon.setImageResource(R.drawable.ic_check);
                    } else {
                        ivSendOrMicIcon.setImageResource(R.drawable.ic_send);
                    }
                    ivSendOrMicIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
                } else {
                    ivSendBgCircle.setVisibility(View.GONE);
                    ivSendOrMicIcon.setImageResource(R.drawable.ic_mic);
                    ivSendOrMicIcon.setColorFilter(0xFF7F91A4, PorterDuff.Mode.SRC_IN);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Touch listener for press-and-hold voice message recording with slide-to-cancel
        btnSendOrMic.setOnTouchListener((v, event) -> {
            String text = etMessageInput.getText().toString().trim();
            if (!text.isEmpty() || editingMessage != null) {
                return false;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    voiceTouchStartX = event.getRawX();
                    isVoiceRecordingCancelled = false;
                    checkAndStartVoiceRecording();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (TelegramVoiceRecorder.getInstance().isRecording()) {
                        float diffX = voiceTouchStartX - event.getRawX();
                        float threshold = 80 * getResources().getDisplayMetrics().density;
                        if (diffX > threshold) {
                            if (!isVoiceRecordingCancelled) {
                                isVoiceRecordingCancelled = true;
                                tvSlideToCancel.setText("Release to cancel");
                                tvSlideToCancel.setTextColor(0xFFE53935);
                            }
                        } else {
                            if (isVoiceRecordingCancelled) {
                                isVoiceRecordingCancelled = false;
                                tvSlideToCancel.setText("< Slide to cancel");
                                tvSlideToCancel.setTextColor(0xFF7F91A4);
                            }
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (TelegramVoiceRecorder.getInstance().isRecording()) {
                        stopOrCancelVoiceRecording(isVoiceRecordingCancelled);
                    }
                    return true;
            }
            return false;
        });

        btnSendOrMic.setOnClickListener(v -> {
            String text = etMessageInput.getText().toString().trim();
            if (editingMessage != null) {
                submitEditMessage(text);
                return;
            }

            if (!text.isEmpty()) {
                sendMessageToTelegram(text);
            }
        });
    }

    private void checkAndStartVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }

        layoutVoiceRecording.setVisibility(View.VISIBLE);
        etMessageInput.setVisibility(View.GONE);
        btnEmoji.setVisibility(View.GONE);
        btnAttach.setVisibility(View.GONE);
        ivSendBgCircle.setVisibility(View.VISIBLE);
        ivSendOrMicIcon.setImageResource(R.drawable.ic_mic);
        ivSendOrMicIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        tvRecordDuration.setText("0:00");
        tvSlideToCancel.setText("< Slide to cancel");
        tvSlideToCancel.setTextColor(0xFF7F91A4);

        TelegramVoiceRecorder.getInstance().startRecording(this, new TelegramVoiceRecorder.RecordCallback() {
            @Override
            public void onTimerTick(int durationSeconds) {
                runOnUiThread(() -> tvRecordDuration.setText(TelegramMediaHelper.formatDuration(durationSeconds)));
            }

            @Override
            public void onRecordingFinished(File voiceFile, int durationSeconds, byte[] waveform) {
                runOnUiThread(() -> {
                    resetVoiceRecordingUi();
                    if (durationSeconds < 1) {
                        Toast.makeText(ChatActivity.this, "Hold to record, release to send", Toast.LENGTH_SHORT).show();
                        if (voiceFile != null && voiceFile.exists()) voiceFile.delete();
                        return;
                    }

                    if (activeClient != null && voiceFile != null && voiceFile.exists()) {
                        long replyToId = (replyingToMessage != null) ? replyingToMessage.id : 0;
                        cancelReplyMode();
                        activeClient.sendVoiceNote(chatId, replyToId, voiceFile.getAbsolutePath(), durationSeconds, waveform, null, result -> {
                            if (result instanceof TdApi.Error) {
                                TdApi.Error err = (TdApi.Error) result;
                                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Failed to send voice: " + err.message, Toast.LENGTH_SHORT).show());
                            }
                        });
                    }
                });
            }

            @Override
            public void onRecordingError(String message) {
                runOnUiThread(() -> {
                    resetVoiceRecordingUi();
                    Toast.makeText(ChatActivity.this, "Voice error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void stopOrCancelVoiceRecording(boolean cancel) {
        if (cancel) {
            TelegramVoiceRecorder.getInstance().cancelRecording();
            resetVoiceRecordingUi();
            Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show();
        } else {
            TelegramVoiceRecorder.getInstance().stopRecording();
        }
    }

    private void resetVoiceRecordingUi() {
        layoutVoiceRecording.setVisibility(View.GONE);
        etMessageInput.setVisibility(View.VISIBLE);
        btnEmoji.setVisibility(View.VISIBLE);
        btnAttach.setVisibility(View.VISIBLE);
        boolean hasText = etMessageInput.getText().toString().trim().length() > 0;
        if (!hasText && editingMessage == null) {
            ivSendBgCircle.setVisibility(View.GONE);
            ivSendOrMicIcon.setImageResource(R.drawable.ic_mic);
            ivSendOrMicIcon.setColorFilter(0xFF7F91A4, PorterDuff.Mode.SRC_IN);
        }
    }

    private void connectTdLibAndLoadMessages() {
        AccountManager accManager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = accManager != null ? accManager.getActiveAccount() : null;
        if (active == null) {
            Toast.makeText(this, "No active Telegram account", Toast.LENGTH_SHORT).show();
            return;
        }

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        if (tdManager == null) return;

        activeClient = tdManager.getOrCreateClient(active.id);
        if (activeClient == null) return;

        activeClient.addRawUpdateListener(rawUpdateListener);

        // Tell TDLib this chat is opened
        activeClient.send(new TdApi.OpenChat(chatId), null);

        // Request chat metadata for avatar & subtitle
        activeClient.send(new TdApi.GetChat(chatId), result -> {
            if (result instanceof TdApi.Chat) {
                TdApi.Chat chat = (TdApi.Chat) result;
                runOnUiThread(() -> updateChatMetadata(chat));
            }
        });

        // Request initial chat history (latest 50 messages)
        pbLoadingMessages.setVisibility(View.VISIBLE);
        final long requestChatId = chatId;

        activeClient.getChatHistory(requestChatId, 0, 0, PAGE_LIMIT, false, result -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || chatId != requestChatId) {
                    return;
                }

                pbLoadingMessages.setVisibility(View.GONE);
                if (result instanceof TdApi.Messages) {
                    TdApi.Messages msgs = (TdApi.Messages) result;
                    if (msgs.messages != null && msgs.messages.length > 0) {
                        messageAdapter.setMessages(Arrays.asList(msgs.messages));
                        recyclerViewMessages.scrollToPosition(messageAdapter.getItemCount() - 1);
                        tvEmptyMessages.setVisibility(View.GONE);

                        hasReachedBeginningOfHistory = false;

                        // If TDLib only returned a few local cached messages, prefetch older history from server
                        if (msgs.messages.length < 20) {
                            loadOlderChatHistory();
                        }

                        // Mark messages as read
                        long[] ids = new long[msgs.messages.length];
                        for (int i = 0; i < msgs.messages.length; i++) {
                            ids[i] = msgs.messages[i].id;
                        }
                        activeClient.send(new TdApi.ViewMessages(chatId, ids, null, true), null);
                    } else {
                        hasReachedBeginningOfHistory = true;
                        tvEmptyMessages.setVisibility(View.VISIBLE);
                    }
                } else {
                    tvEmptyMessages.setVisibility(View.VISIBLE);
                }

                // Check for pinned message
                loadPinnedMessage();
            });
        });
    }

    private synchronized void loadOlderChatHistory() {
        if (isLoadingOlderHistory || hasReachedBeginningOfHistory || activeClient == null) {
            return;
        }

        long oldestId = messageAdapter.getOldestMessageId();
        if (oldestId <= 0) {
            return;
        }

        isLoadingOlderHistory = true;
        pbLoadingOlderMessages.setVisibility(View.VISIBLE);

        final long requestChatId = chatId;
        activeClient.getChatHistory(requestChatId, oldestId, 0, PAGE_LIMIT, false, result -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || chatId != requestChatId) {
                    return;
                }

                pbLoadingOlderMessages.setVisibility(View.GONE);
                isLoadingOlderHistory = false;

                if (result instanceof TdApi.Messages) {
                    TdApi.Messages msgs = (TdApi.Messages) result;
                    if (msgs.messages != null && msgs.messages.length > 0) {
                        List<TdApi.Message> genuinelyOlder = new ArrayList<>();
                        for (TdApi.Message m : msgs.messages) {
                            if (m != null && m.id != oldestId && !messageAdapter.containsMessage(m.id)) {
                                genuinelyOlder.add(m);
                            }
                        }

                        if (genuinelyOlder.isEmpty()) {
                            hasReachedBeginningOfHistory = true;
                        } else {
                            int firstVisible = layoutManager.findFirstVisibleItemPosition();
                            View topView = layoutManager.findViewByPosition(firstVisible);
                            int topOffset = (topView != null) ? (topView.getTop() - recyclerViewMessages.getPaddingTop()) : 0;

                            int insertedCount = messageAdapter.prependOlderMessages(genuinelyOlder);

                            if (firstVisible != RecyclerView.NO_POSITION && insertedCount > 0) {
                                layoutManager.scrollToPositionWithOffset(firstVisible + insertedCount, topOffset);
                            }
                        }
                    } else {
                        hasReachedBeginningOfHistory = true;
                    }
                } else if (result instanceof TdApi.Error) {
                    Log.e(TAG, "Error loading older history: " + ((TdApi.Error) result).message);
                }
            });
        });
    }

    private void updateChatMetadata(TdApi.Chat chat) {
        if (chat == null) return;
        if (chat.title != null && !chat.title.isEmpty()) {
            tvChatTitle.setText(chat.title);
            tvChatAvatarInitials.setText(TelegramAvatarHelper.getInitials(chat.title));
        }

        if (chatId == 777000L || (chat.title != null && chat.title.toLowerCase().contains("telegram"))) {
            tvChatSubtitle.setText("service notifications");
        } else if (chat.type instanceof TdApi.ChatTypeSupergroup) {
            TdApi.ChatTypeSupergroup sg = (TdApi.ChatTypeSupergroup) chat.type;
            tvChatSubtitle.setText(sg.isChannel ? "channel" : "group");
            messageAdapter.setGroupChat(!sg.isChannel);
        } else if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
            tvChatSubtitle.setText("group");
            messageAdapter.setGroupChat(true);
        } else if (chat.type instanceof TdApi.ChatTypePrivate) {
            tvChatSubtitle.setText("last seen recently");
            messageAdapter.setGroupChat(false);
        }

        if (chat.photo != null && chat.photo.small != null) {
            avatarFileId = chat.photo.small.id;
            if (chat.photo.small.local.isDownloadingCompleted &&
                chat.photo.small.local.path != null &&
                !chat.photo.small.local.path.isEmpty()) {
                loadAvatarPhoto(chat.photo.small.local.path);
            } else if (!chat.photo.small.local.isDownloadingActive) {
                activeClient.send(new TdApi.DownloadFile(chat.photo.small.id, 1, 0, 0, false), null);
            }
        }
    }

    private void loadAvatarPhoto(String path) {
        RoundedBitmapDrawable drawable = TelegramAvatarHelper.loadCircularBitmap(this, path);
        if (drawable != null) {
            ivChatAvatarPhoto.setImageDrawable(drawable);
            ivChatAvatarPhoto.setVisibility(View.VISIBLE);
            tvChatAvatarInitials.setVisibility(View.GONE);
        }
    }

    private void loadPinnedMessage() {
        if (activeClient == null) return;
        activeClient.getChatPinnedMessage(chatId, result -> {
            runOnUiThread(() -> {
                if (result instanceof TdApi.Message) {
                    currentPinnedMessage = (TdApi.Message) result;
                    layoutPinnedBar.setVisibility(View.VISIBLE);
                    String snippet = getMessageText(currentPinnedMessage);
                    tvPinnedSnippet.setText(snippet.isEmpty() ? "Pinned message" : snippet);
                } else {
                    currentPinnedMessage = null;
                    layoutPinnedBar.setVisibility(View.GONE);
                }
            });
        });
    }

    private void sendMessageToTelegram(String text) {
        if (activeClient == null) return;

        etMessageInput.setText("");

        TdApi.FormattedText formattedText = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        TdApi.InputMessageText inputContent = new TdApi.InputMessageText(formattedText, null, true);

        TdApi.InputMessageReplyTo replyTo = (replyingToMessage != null)
            ? new TdApi.InputMessageReplyToMessage(replyingToMessage.id, null, 0, null)
            : null;

        TdApi.SendMessage sendRequest = new TdApi.SendMessage(chatId, null, replyTo, null, null, inputContent);

        cancelReplyMode();

        activeClient.send(sendRequest, result -> {
            if (result instanceof TdApi.Error) {
                TdApi.Error err = (TdApi.Error) result;
                runOnUiThread(() ->
                    Toast.makeText(this, "Failed to send: " + err.message, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void submitEditMessage(String newText) {
        if (activeClient == null || editingMessage == null) return;

        if (newText.isEmpty()) {
            Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        final long targetMessageId = editingMessage.id;
        activeClient.editMessageText(chatId, targetMessageId, newText, result -> {
            runOnUiThread(() -> {
                if (result instanceof TdApi.Error) {
                    Toast.makeText(this, "Edit failed: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Message edited", Toast.LENGTH_SHORT).show();
                }
            });
        });

        cancelEditMode();
    }

    // ==========================================
    // MEDIA ATTACHMENTS & SENDING (Milestone 2.8)
    // ==========================================

    private void showAttachmentPicker() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheet = LayoutInflater.from(this).inflate(R.layout.dialog_attachment_picker, null);
        dialog.setContentView(sheet);

        sheet.findViewById(R.id.btnAttachGallery).setOnClickListener(v -> {
            dialog.dismiss();
            pickPhotoLauncher.launch("image/*");
        });

        sheet.findViewById(R.id.btnAttachVideo).setOnClickListener(v -> {
            dialog.dismiss();
            pickVideoLauncher.launch("video/*");
        });

        sheet.findViewById(R.id.btnAttachAudio).setOnClickListener(v -> {
            dialog.dismiss();
            pickAudioLauncher.launch("audio/*");
        });

        sheet.findViewById(R.id.btnAttachFile).setOnClickListener(v -> {
            dialog.dismiss();
            pickDocumentLauncher.launch("*/*");
        });

        dialog.show();
    }

    private void handleSelectedMediaUri(Uri uri, String mediaType) {
        if (uri == null) return;
        new Thread(() -> {
            String originalName = TelegramMediaHelper.getFileName(this, uri);
            File cachedFile = TelegramMediaHelper.copyUriToCache(this, uri, originalName);
            if (cachedFile == null || !cachedFile.exists()) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show());
                return;
            }

            runOnUiThread(() -> showMediaSendPreviewDialog(cachedFile, mediaType, originalName));
        }).start();
    }

    private void showMediaSendPreviewDialog(File cachedFile, String mediaType, String fileName) {
        BottomSheetDialog previewDialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_media_send_preview, null);
        previewDialog.setContentView(view);

        ImageView ivPreviewPhoto = view.findViewById(R.id.ivSendPreviewPhoto);
        LinearLayout layoutPreviewDoc = view.findViewById(R.id.layoutSendPreviewDoc);
        TextView tvDocName = view.findViewById(R.id.tvSendPreviewFileName);
        TextView tvDocSize = view.findViewById(R.id.tvSendPreviewFileSize);
        EditText etCaption = view.findViewById(R.id.etSendPreviewCaption);
        View btnCancel = view.findViewById(R.id.btnSendPreviewCancel);
        View btnSend = view.findViewById(R.id.btnSendPreviewConfirm);

        if ("image".equals(mediaType)) {
            ivPreviewPhoto.setVisibility(View.VISIBLE);
            layoutPreviewDoc.setVisibility(View.GONE);
            android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(cachedFile.getAbsolutePath(), 600, 400);
            if (bm != null) {
                ivPreviewPhoto.setImageBitmap(bm);
            }
        } else if ("video".equals(mediaType)) {
            ivPreviewPhoto.setVisibility(View.VISIBLE);
            layoutPreviewDoc.setVisibility(View.GONE);
            android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(cachedFile.getAbsolutePath(), 600, 400);
            if (bm != null) {
                ivPreviewPhoto.setImageBitmap(bm);
            } else {
                ivPreviewPhoto.setImageResource(R.drawable.ic_attach_video);
            }
        } else {
            ivPreviewPhoto.setVisibility(View.GONE);
            layoutPreviewDoc.setVisibility(View.VISIBLE);
            tvDocName.setText(fileName);
            tvDocSize.setText(TelegramMediaHelper.formatFileSize(cachedFile.length()));
        }

        btnCancel.setOnClickListener(v -> previewDialog.dismiss());

        btnSend.setOnClickListener(v -> {
            String caption = etCaption.getText().toString().trim();
            previewDialog.dismiss();
            sendMediaMessage(cachedFile, mediaType, caption);
        });

        previewDialog.show();
    }

    private void sendMediaMessage(File cachedFile, String mediaType, String caption) {
        if (activeClient == null || cachedFile == null) return;

        long replyToId = (replyingToMessage != null) ? replyingToMessage.id : 0;
        cancelReplyMode();

        String filePath = cachedFile.getAbsolutePath();

        if ("image".equals(mediaType)) {
            TelegramMediaHelper.MediaDimensions dims = TelegramMediaHelper.getImageDimensions(filePath);
            activeClient.sendPhoto(chatId, replyToId, filePath, dims.width, dims.height, caption, result -> {
                if (result instanceof TdApi.Error) {
                    TdApi.Error err = (TdApi.Error) result;
                    runOnUiThread(() -> Toast.makeText(this, "Failed to send photo: " + err.message, Toast.LENGTH_SHORT).show());
                }
            });
        } else if ("video".equals(mediaType)) {
            TelegramMediaHelper.MediaDimensions dims = TelegramMediaHelper.getVideoMetadata(filePath);
            activeClient.sendVideo(chatId, replyToId, filePath, dims.duration, dims.width, dims.height, caption, result -> {
                if (result instanceof TdApi.Error) {
                    TdApi.Error err = (TdApi.Error) result;
                    runOnUiThread(() -> Toast.makeText(this, "Failed to send video: " + err.message, Toast.LENGTH_SHORT).show());
                }
            });
        } else if ("audio".equals(mediaType)) {
            TelegramMediaHelper.AudioMetadata meta = TelegramMediaHelper.getAudioMetadata(filePath);
            activeClient.sendAudio(chatId, replyToId, filePath, meta.duration, meta.title, meta.performer, caption, result -> {
                if (result instanceof TdApi.Error) {
                    TdApi.Error err = (TdApi.Error) result;
                    runOnUiThread(() -> Toast.makeText(this, "Failed to send audio: " + err.message, Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            activeClient.sendDocument(chatId, replyToId, filePath, caption, result -> {
                if (result instanceof TdApi.Error) {
                    TdApi.Error err = (TdApi.Error) result;
                    runOnUiThread(() -> Toast.makeText(this, "Failed to send file: " + err.message, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    // ==========================================
    // MESSAGE ACTIONS (Milestone 2.7)
    // ==========================================

    @Override
    public void onMessageClicked(TdApi.Message message, int position) {
        openMessageActionsMenu(message);
    }

    @Override
    public void onMessageLongClicked(TdApi.Message message, int position) {
        openMessageActionsMenu(message);
    }

    @Override
    public void onReplyClicked(long repliedMessageId) {
        scrollToMessage(repliedMessageId);
    }

    @Override
    public void onSelectionChanged(int selectedCount) {
        updateSelectionToolbar(selectedCount);
    }

    @Override
    public void onMediaClicked(TdApi.Message message, TdApi.File file, String mimeType) {
        if (file == null || file.local == null || !file.local.isDownloadingCompleted || file.local.path == null || file.local.path.isEmpty()) {
            return;
        }

        if (message.content instanceof TdApi.MessageDocument) {
            TelegramMediaHelper.openFileWithSystem(this, file.local.path, mimeType);
        } else {
            Intent intent = new Intent(this, MediaViewerActivity.class);
            intent.putExtra("file_path", file.local.path);
            intent.putExtra("mime_type", mimeType);
            intent.putExtra("title", chatTitle);
            intent.putExtra("subtitle", TelegramDateFormatter.formatChatTimestamp(message.date));
            intent.putExtra("caption", getMessageCaption(message));
            startActivity(intent);
        }
    }

    @Override
    public void onDownloadClicked(TdApi.Message message, int fileId) {
        if (activeClient != null && fileId > 0) {
            activeClient.downloadFile(fileId, 1, null);
        }
    }

    @Override
    public void onRequestSenderName(TdApi.MessageSender sender) {
        if (activeClient == null || sender == null) return;
        if (sender instanceof TdApi.MessageSenderUser) {
            long userId = ((TdApi.MessageSenderUser) sender).userId;
            activeClient.getUser(userId, result -> {
                if (result instanceof TdApi.User) {
                    TdApi.User user = (TdApi.User) result;
                    String name = user.firstName;
                    if (user.lastName != null && !user.lastName.isEmpty()) {
                        name = (name == null || name.isEmpty()) ? user.lastName : name + " " + user.lastName;
                    }
                    if (name == null || name.isEmpty()) {
                        name = (user.usernames != null && user.usernames.activeUsernames != null && user.usernames.activeUsernames.length > 0)
                                ? "@" + user.usernames.activeUsernames[0] : "User";
                    }
                    final String finalName = name;
                    runOnUiThread(() -> messageAdapter.setSenderName(userId, finalName));
                }
            });
        } else if (sender instanceof TdApi.MessageSenderChat) {
            long senderChatId = ((TdApi.MessageSenderChat) sender).chatId;
            activeClient.send(new TdApi.GetChat(senderChatId), result -> {
                if (result instanceof TdApi.Chat) {
                    TdApi.Chat chat = (TdApi.Chat) result;
                    final String title = chat.title != null ? chat.title : "Chat";
                    runOnUiThread(() -> messageAdapter.setSenderName(senderChatId, title));
                }
            });
        }
    }

    private void updateSelectionToolbar(int selectedCount) {
        if (selectedCount > 0) {
            chatToolbar.setVisibility(View.GONE);
            selectionToolbar.setVisibility(View.VISIBLE);
            tvSelectionCount.setText(selectedCount + " selected");
            layoutBottomInput.setVisibility(View.GONE);
            layoutComposerHeader.setVisibility(View.GONE);
        } else {
            chatToolbar.setVisibility(View.VISIBLE);
            selectionToolbar.setVisibility(View.GONE);
            layoutBottomInput.setVisibility(View.VISIBLE);
            if (replyingToMessage != null || editingMessage != null) {
                layoutComposerHeader.setVisibility(View.VISIBLE);
            }
        }
    }

    private void scrollToMessage(long targetMessageId) {
        int pos = messageAdapter.findPositionByMessageId(targetMessageId);
        if (pos != -1) {
            recyclerViewMessages.smoothScrollToPosition(pos);
        } else {
            Toast.makeText(this, "Original message is older, scroll up to load", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMessageActionsMenu(TdApi.Message message) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_message_actions, null);
        dialog.setContentView(view);

        View actionReply = view.findViewById(R.id.actionReply);
        View actionEdit = view.findViewById(R.id.actionEdit);
        View actionCopy = view.findViewById(R.id.actionCopy);
        View actionForward = view.findViewById(R.id.actionForward);
        View actionPin = view.findViewById(R.id.actionPin);
        ImageView ivActionPinIcon = view.findViewById(R.id.ivActionPinIcon);
        TextView tvActionPinText = view.findViewById(R.id.tvActionPinText);
        View actionSelect = view.findViewById(R.id.actionSelect);
        View actionDelete = view.findViewById(R.id.actionDelete);

        // Edit is permitted only for user's outgoing text messages that are delivered
        boolean canEdit = message.isOutgoing && (message.content instanceof TdApi.MessageText) && (message.sendingState == null);
        actionEdit.setVisibility(canEdit ? View.VISIBLE : View.GONE);

        // Pin / Unpin label
        if (message.isPinned) {
            tvActionPinText.setText("Unpin");
        } else {
            tvActionPinText.setText("Pin");
        }

        // Copy is visible if text is present
        String msgText = getMessageText(message);
        actionCopy.setVisibility(!msgText.isEmpty() ? View.VISIBLE : View.GONE);

        actionReply.setOnClickListener(v -> {
            dialog.dismiss();
            startReplyMode(message);
        });

        actionEdit.setOnClickListener(v -> {
            dialog.dismiss();
            startEditMode(message);
        });

        actionCopy.setOnClickListener(v -> {
            dialog.dismiss();
            copyMessageText(Collections.singletonList(message));
        });

        actionForward.setOnClickListener(v -> {
            dialog.dismiss();
            showForwardDialog(Collections.singletonList(message.id));
        });

        actionPin.setOnClickListener(v -> {
            dialog.dismiss();
            togglePinMessage(message);
        });

        actionSelect.setOnClickListener(v -> {
            dialog.dismiss();
            messageAdapter.startSelection(message.id);
        });

        actionDelete.setOnClickListener(v -> {
            dialog.dismiss();
            showDeleteConfirmationDialog(Collections.singletonList(message.id), message.isOutgoing);
        });

        dialog.show();
    }

    private void startReplyMode(TdApi.Message message) {
        cancelEditMode();
        replyingToMessage = message;

        layoutComposerHeader.setVisibility(View.VISIBLE);
        ivComposerHeaderIcon.setImageResource(R.drawable.ic_reply);
        tvComposerHeaderTitle.setText("Reply to Message");
        String snippet = getMessageText(message);
        tvComposerHeaderSubtitle.setText(snippet.isEmpty() ? "Message" : snippet);

        etMessageInput.requestFocus();
        showKeyboard();
    }

    private void cancelReplyMode() {
        replyingToMessage = null;
        if (editingMessage == null) {
            layoutComposerHeader.setVisibility(View.GONE);
        }
    }

    private void startEditMode(TdApi.Message message) {
        cancelReplyMode();
        editingMessage = message;

        layoutComposerHeader.setVisibility(View.VISIBLE);
        ivComposerHeaderIcon.setImageResource(R.drawable.ic_edit);
        tvComposerHeaderTitle.setText("Edit Message");
        String originalText = getMessageText(message);
        tvComposerHeaderSubtitle.setText(originalText);

        etMessageInput.setText(originalText);
        etMessageInput.setSelection(originalText.length());
        etMessageInput.requestFocus();

        ivSendBgCircle.setVisibility(View.VISIBLE);
        ivSendOrMicIcon.setImageResource(R.drawable.ic_check);
        ivSendOrMicIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);

        showKeyboard();
    }

    private void cancelEditMode() {
        editingMessage = null;
        etMessageInput.setText("");
        if (replyingToMessage == null) {
            layoutComposerHeader.setVisibility(View.GONE);
        }
        ivSendBgCircle.setVisibility(View.GONE);
        ivSendOrMicIcon.setImageResource(R.drawable.ic_mic);
        ivSendOrMicIcon.setColorFilter(0xFF7F91A4, PorterDuff.Mode.SRC_IN);
    }

    private void cancelActiveComposerAction() {
        cancelReplyMode();
        cancelEditMode();
    }

    private void copyMessageText(List<TdApi.Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            String text = getMessageText(messages.get(i));
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(text);
            }
        }

        if (sb.length() == 0) {
            Toast.makeText(this, "No text to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Telegram message", sb.toString());
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show();

        if (messageAdapter.isSelectionMode()) {
            messageAdapter.clearSelection();
        }
    }

    private void togglePinMessage(TdApi.Message message) {
        if (activeClient == null || message == null) return;

        if (message.isPinned) {
            activeClient.unpinChatMessage(chatId, message.id, result -> {
                runOnUiThread(() -> {
                    if (result instanceof TdApi.Error) {
                        Toast.makeText(this, "Unpin failed: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
                        loadPinnedMessage();
                    }
                });
            });
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Pin Message");
            builder.setMessage("Do you want to pin this message in " + chatTitle + "?");

            View dialogView = LayoutInflater.from(this).inflate(android.R.layout.select_dialog_item, null);
            CheckBox cbNotify = new CheckBox(this);
            cbNotify.setText("Notify all members");
            cbNotify.setChecked(true);
            cbNotify.setPadding(32, 16, 32, 16);
            builder.setView(cbNotify);

            builder.setPositiveButton("Pin", (d, which) -> {
                boolean notify = cbNotify.isChecked();
                activeClient.pinChatMessage(chatId, message.id, !notify, false, result -> {
                    runOnUiThread(() -> {
                        if (result instanceof TdApi.Error) {
                            Toast.makeText(this, "Pin failed: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Message pinned", Toast.LENGTH_SHORT).show();
                            loadPinnedMessage();
                        }
                    });
                });
            });
            builder.setNegativeButton("Cancel", null);
            builder.show();
        }
    }

    private void showDeleteConfirmationDialog(List<Long> messageIds, boolean isOutgoing) {
        if (messageIds == null || messageIds.isEmpty() || activeClient == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(messageIds.size() == 1 ? "Delete Message" : "Delete " + messageIds.size() + " Messages");
        builder.setMessage("Are you sure you want to delete the selected message(s)?");

        // Allow "delete for everyone" checkbox if not service notification
        CheckBox cbRevoke = new CheckBox(this);
        cbRevoke.setText("Also delete for " + chatTitle);
        cbRevoke.setChecked(isOutgoing);
        cbRevoke.setPadding(32, 16, 32, 16);
        if (chatId != 777000L) {
            builder.setView(cbRevoke);
        }

        builder.setPositiveButton("Delete", (d, which) -> {
            boolean revoke = cbRevoke.isChecked();
            long[] ids = new long[messageIds.size()];
            for (int i = 0; i < messageIds.size(); i++) {
                ids[i] = messageIds.get(i);
            }

            activeClient.deleteMessages(chatId, ids, revoke, result -> {
                runOnUiThread(() -> {
                    if (result instanceof TdApi.Error) {
                        Toast.makeText(this, "Delete failed: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Message(s) deleted", Toast.LENGTH_SHORT).show();
                    }
                });
            });

            if (messageAdapter.isSelectionMode()) {
                messageAdapter.clearSelection();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showForwardDialog(List<Long> messageIds) {
        if (activeClient == null || messageIds == null || messageIds.isEmpty()) return;

        BottomSheetDialog forwardDialog = new BottomSheetDialog(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_forward_chat, null);
        forwardDialog.setContentView(dialogView);

        RecyclerView rvChats = dialogView.findViewById(R.id.rvForwardChats);
        TextView tvEmpty = dialogView.findViewById(R.id.tvEmptyForwardChats);
        ImageView btnClose = dialogView.findViewById(R.id.btnForwardClose);

        btnClose.setOnClickListener(v -> forwardDialog.dismiss());

        rvChats.setLayoutManager(new LinearLayoutManager(this));

        List<TdApi.Chat> known = activeClient.getKnownChats();
        // Exclude current chat from destination list if possible, or allow sending to self
        List<TdApi.Chat> forwardTargets = new ArrayList<>();
        for (TdApi.Chat c : known) {
            if (c != null && c.id != chatId) {
                forwardTargets.add(c);
            }
        }

        if (forwardTargets.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvChats.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            rvChats.setVisibility(View.VISIBLE);
            ForwardChatAdapter adapter = new ForwardChatAdapter(forwardTargets, targetChat -> {
                confirmAndForward(targetChat, messageIds);
                forwardDialog.dismiss();
            });
            rvChats.setAdapter(adapter);
        }

        forwardDialog.show();
    }

    private void confirmAndForward(TdApi.Chat targetChat, List<Long> messageIds) {
        if (activeClient == null || targetChat == null || messageIds.isEmpty()) return;

        long[] ids = new long[messageIds.size()];
        for (int i = 0; i < messageIds.size(); i++) {
            ids[i] = messageIds.get(i);
        }

        activeClient.forwardMessages(targetChat.id, chatId, ids, false, result -> {
            runOnUiThread(() -> {
                if (result instanceof TdApi.Error) {
                    Toast.makeText(this, "Forward failed: " + ((TdApi.Error) result).message, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Forwarded to " + targetChat.title, Toast.LENGTH_SHORT).show();
                }
            });
        });

        if (messageAdapter.isSelectionMode()) {
            messageAdapter.clearSelection();
        }
    }

    private String getMessageCaption(TdApi.Message msg) {
        if (msg == null || msg.content == null) return "";
        if (msg.content instanceof TdApi.MessagePhoto) {
            TdApi.MessagePhoto p = (TdApi.MessagePhoto) msg.content;
            return p.caption != null && p.caption.text != null ? p.caption.text : "";
        } else if (msg.content instanceof TdApi.MessageVideo) {
            TdApi.MessageVideo v = (TdApi.MessageVideo) msg.content;
            return v.caption != null && v.caption.text != null ? v.caption.text : "";
        } else if (msg.content instanceof TdApi.MessageDocument) {
            TdApi.MessageDocument d = (TdApi.MessageDocument) msg.content;
            return d.caption != null && d.caption.text != null ? d.caption.text : "";
        }
        return "";
    }

    private String getMessageText(TdApi.Message msg) {
        if (msg == null || msg.content == null) return "";
        if (msg.content instanceof TdApi.MessageText) {
            TdApi.MessageText textMsg = (TdApi.MessageText) msg.content;
            if (textMsg.text != null && textMsg.text.text != null) {
                return textMsg.text.text;
            }
        }
        String caption = getMessageCaption(msg);
        if (!caption.isEmpty()) {
            return caption;
        }
        return TelegramMessageFormatter.formatMessagePreview(msg);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etMessageInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void onBackPressed() {
        if (messageAdapter != null && messageAdapter.isSelectionMode()) {
            messageAdapter.clearSelection();
            return;
        }
        if (editingMessage != null) {
            cancelEditMode();
            return;
        }
        if (replyingToMessage != null) {
            cancelReplyMode();
            return;
        }
        super.onBackPressed();
    }

    private void handleIncomingTdlibUpdate(TdApi.Object update) {
        if (update instanceof TdApi.UpdateNewMessage) {
            TdApi.UpdateNewMessage updateMsg = (TdApi.UpdateNewMessage) update;
            if (updateMsg.message.chatId == chatId) {
                runOnUiThread(() -> {
                    boolean isNearBottom = layoutManager.findLastVisibleItemPosition() >= messageAdapter.getItemCount() - 3;
                    messageAdapter.appendOrUpdateMessage(updateMsg.message);

                    if (isNearBottom || updateMsg.message.isOutgoing) {
                        recyclerViewMessages.smoothScrollToPosition(messageAdapter.getItemCount() - 1);
                    }
                    tvEmptyMessages.setVisibility(View.GONE);

                    if (!updateMsg.message.isOutgoing && activeClient != null) {
                        activeClient.send(new TdApi.ViewMessages(chatId, new long[]{updateMsg.message.id}, null, true), null);
                    }
                });
            }
        } else if (update instanceof TdApi.UpdateMessageSendSucceeded) {
            TdApi.UpdateMessageSendSucceeded succ = (TdApi.UpdateMessageSendSucceeded) update;
            if (succ.message.chatId == chatId) {
                runOnUiThread(() -> messageAdapter.onMessageSendSucceeded(succ.oldMessageId, succ.message));
            }
        } else if (update instanceof TdApi.UpdateMessageSendFailed) {
            TdApi.UpdateMessageSendFailed fail = (TdApi.UpdateMessageSendFailed) update;
            if (fail.message.chatId == chatId) {
                runOnUiThread(() -> {
                    messageAdapter.onMessageSendFailed(fail.oldMessageId, fail.message);
                    String errMsg = fail.error != null ? fail.error.message : "Error";
                    Toast.makeText(this, "Send failed: " + errMsg, Toast.LENGTH_SHORT).show();
                });
            }
        } else if (update instanceof TdApi.UpdateMessageContent) {
            TdApi.UpdateMessageContent updateContent = (TdApi.UpdateMessageContent) update;
            if (updateContent.chatId == chatId) {
                runOnUiThread(() -> messageAdapter.onMessageContentUpdated(updateContent.messageId, updateContent.newContent));
            }
        } else if (update instanceof TdApi.UpdateMessageEdited) {
            TdApi.UpdateMessageEdited updateEdited = (TdApi.UpdateMessageEdited) update;
            if (updateEdited.chatId == chatId) {
                runOnUiThread(() -> messageAdapter.onMessageEdited(updateEdited.messageId, updateEdited.editDate));
            }
        } else if (update instanceof TdApi.UpdateDeleteMessages) {
            TdApi.UpdateDeleteMessages updateDel = (TdApi.UpdateDeleteMessages) update;
            if (updateDel.chatId == chatId) {
                runOnUiThread(() -> {
                    messageAdapter.onMessagesDeleted(updateDel.messageIds);
                    if (messageAdapter.getMessageCount() == 0) {
                        tvEmptyMessages.setVisibility(View.VISIBLE);
                    }
                    if (currentPinnedMessage != null) {
                        for (long delId : updateDel.messageIds) {
                            if (delId == currentPinnedMessage.id) {
                                loadPinnedMessage();
                                break;
                            }
                        }
                    }
                });
            }
        } else if (update instanceof TdApi.UpdateMessageIsPinned) {
            TdApi.UpdateMessageIsPinned pinUpdate = (TdApi.UpdateMessageIsPinned) update;
            if (pinUpdate.chatId == chatId) {
                runOnUiThread(this::loadPinnedMessage);
            }
        } else if (update instanceof TdApi.UpdateChatReadOutbox) {
            TdApi.UpdateChatReadOutbox readOutbox = (TdApi.UpdateChatReadOutbox) update;
            if (readOutbox.chatId == chatId) {
                runOnUiThread(() -> messageAdapter.setLastReadOutboxMessageId(readOutbox.lastReadOutboxMessageId));
            }
        } else if (update instanceof TdApi.UpdateFile) {
            TdApi.UpdateFile fileUpdate = (TdApi.UpdateFile) update;
            runOnUiThread(() -> {
                messageAdapter.onFileUpdated(fileUpdate.file);
                if (fileUpdate.file.id == avatarFileId && fileUpdate.file.local.isDownloadingCompleted) {
                    loadAvatarPhoto(fileUpdate.file.local.path);
                }
            });
        } else if (update instanceof TdApi.UpdateUser) {
            TdApi.UpdateUser userUpdate = (TdApi.UpdateUser) update;
            if (userUpdate.user != null) {
                String name = userUpdate.user.firstName;
                if (userUpdate.user.lastName != null && !userUpdate.user.lastName.isEmpty()) {
                    name = (name == null || name.isEmpty()) ? userUpdate.user.lastName : name + " " + userUpdate.user.lastName;
                }
                if (name != null && !name.isEmpty()) {
                    final String finalName = name;
                    runOnUiThread(() -> messageAdapter.setSenderName(userUpdate.user.id, finalName));
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        TelegramAudioPlayer.getInstance().stop();
        if (TelegramVoiceRecorder.getInstance().isRecording()) {
            TelegramVoiceRecorder.getInstance().cancelRecording();
            resetVoiceRecordingUi();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        TelegramAudioPlayer.getInstance().stop();
        if (activeClient != null) {
            activeClient.removeRawUpdateListener(rawUpdateListener);
            activeClient.send(new TdApi.CloseChat(chatId), null);
        }
    }

    // ==========================================
    // STICKER & GIF PICKER (Milestone 2.9)
    // ==========================================

    private void showStickerGifPicker() {
        if (activeClient == null) return;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sticker_gif_picker, null);
        dialog.setContentView(view);

        TextView tabStickers = view.findViewById(R.id.tabStickers);
        TextView tabGifs = view.findViewById(R.id.tabGifs);
        LinearLayout layoutStickersView = view.findViewById(R.id.layoutStickersView);
        LinearLayout layoutGifsView = view.findViewById(R.id.layoutGifsView);
        RecyclerView rvStickersGrid = view.findViewById(R.id.rvStickersGrid);
        RecyclerView rvGifsGrid = view.findViewById(R.id.rvGifsGrid);
        ProgressBar pbLoading = view.findViewById(R.id.pbStickerGifLoading);
        TextView tvEmpty = view.findViewById(R.id.tvStickerGifEmpty);

        rvStickersGrid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 5));
        rvGifsGrid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 3));

        tabStickers.setOnClickListener(v -> {
            tabStickers.setTextColor(0xFFFFFFFF);
            tabGifs.setTextColor(0xFF7F91A4);
            layoutStickersView.setVisibility(View.VISIBLE);
            layoutGifsView.setVisibility(View.GONE);
        });

        tabGifs.setOnClickListener(v -> {
            tabGifs.setTextColor(0xFFFFFFFF);
            tabStickers.setTextColor(0xFF7F91A4);
            layoutStickersView.setVisibility(View.GONE);
            layoutGifsView.setVisibility(View.VISIBLE);
            loadSavedGifs(rvGifsGrid, pbLoading, tvEmpty, dialog);
        });

        loadInstalledStickers(rvStickersGrid, pbLoading, tvEmpty, dialog);

        dialog.show();
    }

    private void loadInstalledStickers(RecyclerView rvStickersGrid, ProgressBar pbLoading, TextView tvEmpty, BottomSheetDialog dialog) {
        pbLoading.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        activeClient.getInstalledStickerSets(result -> {
            if (result instanceof TdApi.StickerSets) {
                TdApi.StickerSets sets = (TdApi.StickerSets) result;
                if (sets.sets != null && sets.sets.length > 0) {
                    long firstSetId = sets.sets[0].id;
                    activeClient.getStickerSet(firstSetId, setResult -> {
                        runOnUiThread(() -> {
                            pbLoading.setVisibility(View.GONE);
                            if (setResult instanceof TdApi.StickerSet) {
                                TdApi.StickerSet set = (TdApi.StickerSet) setResult;
                                if (set.stickers != null && set.stickers.length > 0) {
                                    StickerPickerAdapter adapter = new StickerPickerAdapter(set.stickers, sticker -> {
                                        long replyToId = (replyingToMessage != null) ? replyingToMessage.id : 0;
                                        cancelReplyMode();
                                        activeClient.sendSticker(chatId, replyToId, sticker.sticker.id, sticker.width, sticker.height, sticker.emoji, sendRes -> {
                                            if (sendRes instanceof TdApi.Error) {
                                                runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Failed to send sticker", Toast.LENGTH_SHORT).show());
                                            }
                                        });
                                        dialog.dismiss();
                                    });
                                    rvStickersGrid.setAdapter(adapter);
                                } else {
                                    tvEmpty.setText("No stickers in pack");
                                    tvEmpty.setVisibility(View.VISIBLE);
                                }
                            } else {
                                tvEmpty.setText("Failed to load sticker pack");
                                tvEmpty.setVisibility(View.VISIBLE);
                            }
                        });
                    });
                } else {
                    runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        tvEmpty.setText("No sticker packs installed");
                        tvEmpty.setVisibility(View.VISIBLE);
                    });
                }
            } else {
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    tvEmpty.setText("Failed to load stickers");
                    tvEmpty.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void loadSavedGifs(RecyclerView rvGifsGrid, ProgressBar pbLoading, TextView tvEmpty, BottomSheetDialog dialog) {
        pbLoading.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        activeClient.getSavedAnimations(result -> {
            runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                if (result instanceof TdApi.Animations) {
                    TdApi.Animations anims = (TdApi.Animations) result;
                    if (anims.animations != null && anims.animations.length > 0) {
                        GifPickerAdapter adapter = new GifPickerAdapter(anims.animations, anim -> {
                            long replyToId = (replyingToMessage != null) ? replyingToMessage.id : 0;
                            cancelReplyMode();
                            if (anim.animation.local != null && anim.animation.local.isDownloadingCompleted &&
                                anim.animation.local.path != null && !anim.animation.local.path.isEmpty()) {
                                activeClient.sendAnimation(chatId, replyToId, anim.animation.local.path, anim.width, anim.height, anim.duration, null, sendRes -> {
                                    if (sendRes instanceof TdApi.Error) {
                                        runOnUiThread(() -> Toast.makeText(ChatActivity.this, "Failed to send GIF", Toast.LENGTH_SHORT).show());
                                    }
                                });
                                dialog.dismiss();
                            } else {
                                Toast.makeText(ChatActivity.this, "Downloading GIF...", Toast.LENGTH_SHORT).show();
                                activeClient.downloadFile(anim.animation.id, 1, dlRes -> {
                                    if (dlRes instanceof TdApi.File) {
                                        TdApi.File f = (TdApi.File) dlRes;
                                        if (f.local.isDownloadingCompleted && f.local.path != null && !f.local.path.isEmpty()) {
                                            activeClient.sendAnimation(chatId, replyToId, f.local.path, anim.width, anim.height, anim.duration, null, null);
                                            runOnUiThread(dialog::dismiss);
                                        }
                                    }
                                });
                            }
                        });
                        rvGifsGrid.setAdapter(adapter);
                    } else {
                        tvEmpty.setText("No saved GIFs found");
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                } else {
                    tvEmpty.setText("Failed to load GIFs");
                    tvEmpty.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private static class StickerPickerAdapter extends RecyclerView.Adapter<StickerPickerAdapter.ViewHolder> {
        interface OnStickerClickListener {
            void onStickerClick(TdApi.Sticker sticker);
        }

        private final TdApi.Sticker[] stickers;
        private final OnStickerClickListener listener;

        StickerPickerAdapter(TdApi.Sticker[] stickers, OnStickerClickListener listener) {
            this.stickers = stickers;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sticker_picker, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TdApi.Sticker sticker = stickers[position];
            if (sticker.thumbnail != null && sticker.thumbnail.file != null &&
                sticker.thumbnail.file.local != null && sticker.thumbnail.file.local.isDownloadingCompleted &&
                sticker.thumbnail.file.local.path != null && !sticker.thumbnail.file.local.path.isEmpty()) {
                android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(sticker.thumbnail.file.local.path, 120, 120);
                if (bm != null) {
                    holder.ivStickerThumb.setImageBitmap(bm);
                } else {
                    holder.ivStickerThumb.setImageResource(R.drawable.ic_sticker);
                }
            } else if (sticker.sticker != null && sticker.sticker.local != null &&
                sticker.sticker.local.isDownloadingCompleted && sticker.sticker.local.path != null &&
                !sticker.sticker.local.path.isEmpty()) {
                android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(sticker.sticker.local.path, 120, 120);
                if (bm != null) {
                    holder.ivStickerThumb.setImageBitmap(bm);
                } else {
                    holder.ivStickerThumb.setImageResource(R.drawable.ic_sticker);
                }
            } else {
                holder.ivStickerThumb.setImageResource(R.drawable.ic_sticker);
            }

            holder.itemView.setOnClickListener(v -> listener.onStickerClick(sticker));
        }

        @Override
        public int getItemCount() {
            return stickers != null ? stickers.length : 0;
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivStickerThumb;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivStickerThumb = itemView.findViewById(R.id.ivStickerThumb);
            }
        }
    }

    private static class GifPickerAdapter extends RecyclerView.Adapter<GifPickerAdapter.ViewHolder> {
        interface OnGifClickListener {
            void onGifClick(TdApi.Animation animation);
        }

        private final TdApi.Animation[] animations;
        private final OnGifClickListener listener;

        GifPickerAdapter(TdApi.Animation[] animations, OnGifClickListener listener) {
            this.animations = animations;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gif_picker, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TdApi.Animation anim = animations[position];
            if (anim.thumbnail != null && anim.thumbnail.file != null &&
                anim.thumbnail.file.local != null && anim.thumbnail.file.local.isDownloadingCompleted &&
                anim.thumbnail.file.local.path != null && !anim.thumbnail.file.local.path.isEmpty()) {
                android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(anim.thumbnail.file.local.path, 160, 120);
                if (bm != null) {
                    holder.ivGifThumb.setImageBitmap(bm);
                } else {
                    holder.ivGifThumb.setImageResource(R.drawable.ic_gif);
                }
            } else if (anim.animation != null && anim.animation.local != null &&
                anim.animation.local.isDownloadingCompleted && anim.animation.local.path != null &&
                !anim.animation.local.path.isEmpty()) {
                android.graphics.Bitmap bm = TelegramMediaHelper.decodeSampledBitmap(anim.animation.local.path, 160, 120);
                if (bm != null) {
                    holder.ivGifThumb.setImageBitmap(bm);
                } else {
                    holder.ivGifThumb.setImageResource(R.drawable.ic_gif);
                }
            } else {
                holder.ivGifThumb.setImageResource(R.drawable.ic_gif);
            }

            holder.itemView.setOnClickListener(v -> listener.onGifClick(anim));
        }

        @Override
        public int getItemCount() {
            return animations != null ? animations.length : 0;
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivGifThumb;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivGifThumb = itemView.findViewById(R.id.ivGifThumb);
            }
        }
    }

    // ==========================================
    // FORWARD DESTINATION ADAPTER
    // ==========================================

    private static class ForwardChatAdapter extends RecyclerView.Adapter<ForwardChatAdapter.ViewHolder> {
        interface OnChatClickListener {
            void onChatClick(TdApi.Chat chat);
        }

        private final List<TdApi.Chat> chats;
        private final OnChatClickListener listener;

        ForwardChatAdapter(List<TdApi.Chat> chats, OnChatClickListener listener) {
            this.chats = chats;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_forward_chat, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TdApi.Chat chat = chats.get(position);
            holder.tvTitle.setText(chat.title != null ? chat.title : "Chat");
            holder.tvInitials.setText(TelegramAvatarHelper.getInitials(chat.title));
            int color = TelegramAvatarHelper.getAvatarColor(chat.id);
            holder.ivAvatarCircle.setColorFilter(color, PorterDuff.Mode.SRC_IN);

            if (chat.type instanceof TdApi.ChatTypeSupergroup) {
                TdApi.ChatTypeSupergroup sg = (TdApi.ChatTypeSupergroup) chat.type;
                holder.tvSubtitle.setText(sg.isChannel ? "channel" : "group");
            } else if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
                holder.tvSubtitle.setText("group");
            } else {
                holder.tvSubtitle.setText("private chat");
            }

            holder.itemView.setOnClickListener(v -> listener.onChatClick(chat));
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivAvatarCircle;
            final TextView tvInitials;
            final TextView tvTitle;
            final TextView tvSubtitle;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAvatarCircle = itemView.findViewById(R.id.ivForwardAvatarCircle);
                tvInitials = itemView.findViewById(R.id.tvForwardAvatarInitials);
                tvTitle = itemView.findViewById(R.id.tvForwardChatTitle);
                tvSubtitle = itemView.findViewById(R.id.tvForwardChatSubtitle);
            }
        }
    }
}
