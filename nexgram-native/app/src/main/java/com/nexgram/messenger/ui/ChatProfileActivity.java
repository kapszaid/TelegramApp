package com.nexgram.messenger.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.ContactAdapter;
import com.nexgram.messenger.ui.adapter.GroupMemberAdapter;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChatProfileActivity extends AppCompatActivity {
    private static final String TAG = "ChatProfileActivity";

    private long chatId;
    private String accountId;
    private String chatTitle;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private ImageView ivAvatarCircle;
    private TextView tvAvatarInitials;
    private ImageView ivAvatarPhoto;
    private TextView tvTitle;
    private TextView tvSubtitle;

    private CardView cardDescription;
    private TextView tvDescription;

    private CardView cardInviteLink;
    private TextView tvInviteLink;
    private Button btnCopyInviteLink;
    private Button btnShareInviteLink;

    private CardView cardMembers;
    private TextView tvMembersCountHeader;
    private Button btnAddMember;
    private RecyclerView rvMembers;
    private GroupMemberAdapter memberAdapter;

    private Button btnLeaveChat;
    private ProgressBar progressBar;

    private TdApi.Chat currentChat;
    private String currentInviteLinkStr = "";
    private boolean isChannel = false;
    private long basicGroupId = 0;
    private long supergroupId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_profile);

        chatId = getIntent().getLongExtra("chat_id", 0);
        accountId = getIntent().getStringExtra("account_id");
        chatTitle = getIntent().getStringExtra("chat_title");

        resolveAccountClient();
        initViews();
        loadChatDetails();
    }

    private void resolveAccountClient() {
        if (accountId == null || accountId.isEmpty()) {
            AccountManager am = com.nexgram.messenger.NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = am != null ? am.getActiveAccount() : null;
            if (active != null) {
                accountId = active.id;
            }
        }
        if (accountId != null) {
            client = TdLibManager.getInstance().getClient(accountId);
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarProfile);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        ivAvatarCircle = findViewById(R.id.ivProfileAvatarCircle);
        tvAvatarInitials = findViewById(R.id.tvProfileAvatarInitials);
        ivAvatarPhoto = findViewById(R.id.ivProfileAvatarPhoto);
        tvTitle = findViewById(R.id.tvProfileTitle);
        tvSubtitle = findViewById(R.id.tvProfileSubtitle);

        cardDescription = findViewById(R.id.cardDescription);
        tvDescription = findViewById(R.id.tvProfileDescription);

        cardInviteLink = findViewById(R.id.cardInviteLink);
        tvInviteLink = findViewById(R.id.tvInviteLink);
        btnCopyInviteLink = findViewById(R.id.btnCopyInviteLink);
        btnShareInviteLink = findViewById(R.id.btnShareInviteLink);

        cardMembers = findViewById(R.id.cardMembers);
        tvMembersCountHeader = findViewById(R.id.tvMembersCountHeader);
        btnAddMember = findViewById(R.id.btnAddMember);
        rvMembers = findViewById(R.id.rvMembers);

        btnLeaveChat = findViewById(R.id.btnLeaveChat);
        progressBar = findViewById(R.id.pbProfile);

        if (chatTitle != null && !chatTitle.isEmpty()) {
            tvTitle.setText(chatTitle);
            tvAvatarInitials.setText(getInitials(chatTitle));
            ivAvatarCircle.setColorFilter(getColorForId(chatId));
        }

        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        memberAdapter = new GroupMemberAdapter(this, client);
        memberAdapter.setListener(new GroupMemberAdapter.OnMemberClickListener() {
            @Override
            public void onMemberClick(TdApi.ChatMember member, TdApi.User user) {
                if (user != null) {
                    openPrivateChatWithUser(user.id);
                }
            }

            @Override
            public void onMemberLongClick(TdApi.ChatMember member, TdApi.User user) {
                if (user != null) {
                    showMemberOptionsDialog(member, user);
                }
            }
        });
        rvMembers.setAdapter(memberAdapter);

        btnCopyInviteLink.setOnClickListener(v -> copyInviteLink());
        btnShareInviteLink.setOnClickListener(v -> shareInviteLink());
        btnAddMember.setOnClickListener(v -> showAddMemberPicker());
        btnLeaveChat.setOnClickListener(v -> confirmLeaveChat());
    }

    private void loadChatDetails() {
        if (client == null || chatId == 0) return;
        progressBar.setVisibility(View.VISIBLE);

        client.getChat(chatId, object -> runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (object instanceof TdApi.Chat) {
                currentChat = (TdApi.Chat) object;
                bindChatInfo(currentChat);
            }
        }));
    }

    private void bindChatInfo(TdApi.Chat chat) {
        tvTitle.setText(chat.title);
        tvAvatarInitials.setText(getInitials(chat.title));
        ivAvatarCircle.setColorFilter(getColorForId(chat.id));

        if (chat.photo != null && chat.photo.small != null && chat.photo.small.local != null
                && chat.photo.small.local.isDownloadingCompleted
                && new File(chat.photo.small.local.path).exists()) {
            ivAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(chat.photo.small.local.path));
            ivAvatarPhoto.setVisibility(View.VISIBLE);
            tvAvatarInitials.setVisibility(View.GONE);
        }

        if (chat.type instanceof TdApi.ChatTypePrivate) {
            setupPrivateChatProfile((TdApi.ChatTypePrivate) chat.type);
        } else if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
            setupBasicGroupProfile((TdApi.ChatTypeBasicGroup) chat.type);
        } else if (chat.type instanceof TdApi.ChatTypeSupergroup) {
            setupSupergroupProfile((TdApi.ChatTypeSupergroup) chat.type);
        }
    }

    private void setupPrivateChatProfile(TdApi.ChatTypePrivate privateType) {
        cardInviteLink.setVisibility(View.GONE);
        cardMembers.setVisibility(View.GONE);
        btnLeaveChat.setText("Delete Chat");

        client.getUser(privateType.userId, userObj -> {
            if (userObj instanceof TdApi.User) {
                TdApi.User user = (TdApi.User) userObj;
                runOnUiThread(() -> {
                    String status = formatUserStatus(user.status);
                    tvSubtitle.setText(status);
                });
            }
        });

        client.getUserFullInfo(privateType.userId, fullInfoObj -> {
            if (fullInfoObj instanceof TdApi.UserFullInfo) {
                TdApi.UserFullInfo fullInfo = (TdApi.UserFullInfo) fullInfoObj;
                runOnUiThread(() -> {
                    String bio = fullInfo.bio != null ? fullInfo.bio.text : "";
                    if (!bio.isEmpty()) {
                        tvDescription.setText(bio);
                        cardDescription.setVisibility(View.VISIBLE);
                    } else {
                        cardDescription.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    private void setupBasicGroupProfile(TdApi.ChatTypeBasicGroup groupType) {
        basicGroupId = groupType.basicGroupId;
        btnLeaveChat.setText("Leave Group");

        client.getBasicGroupFullInfo(basicGroupId, infoObj -> {
            if (infoObj instanceof TdApi.BasicGroupFullInfo) {
                TdApi.BasicGroupFullInfo info = (TdApi.BasicGroupFullInfo) infoObj;
                runOnUiThread(() -> {
                    if (info.description != null && !info.description.trim().isEmpty()) {
                        tvDescription.setText(info.description);
                        cardDescription.setVisibility(View.VISIBLE);
                    } else {
                        cardDescription.setVisibility(View.GONE);
                    }

                    if (info.inviteLink != null && info.inviteLink.inviteLink != null) {
                        currentInviteLinkStr = info.inviteLink.inviteLink;
                        tvInviteLink.setText(currentInviteLinkStr);
                        cardInviteLink.setVisibility(View.VISIBLE);
                    } else {
                        // Request invite link creation
                        createInviteLinkInternal();
                    }

                    if (info.members != null) {
                        tvSubtitle.setText(info.members.length + " members");
                        tvMembersCountHeader.setText("Members (" + info.members.length + ")");
                        memberAdapter.setMembers(Arrays.asList(info.members));
                        cardMembers.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void setupSupergroupProfile(TdApi.ChatTypeSupergroup supergroupType) {
        supergroupId = supergroupType.supergroupId;
        isChannel = supergroupType.isChannel;

        if (isChannel) {
            btnLeaveChat.setText("Leave Channel");
            tvMembersCountHeader.setText("Subscribers");
        } else {
            btnLeaveChat.setText("Leave Group");
            tvMembersCountHeader.setText("Members");
        }

        client.getSupergroupFullInfo(supergroupId, infoObj -> {
            if (infoObj instanceof TdApi.SupergroupFullInfo) {
                TdApi.SupergroupFullInfo info = (TdApi.SupergroupFullInfo) infoObj;
                runOnUiThread(() -> {
                    if (info.description != null && !info.description.trim().isEmpty()) {
                        tvDescription.setText(info.description);
                        cardDescription.setVisibility(View.VISIBLE);
                    } else {
                        cardDescription.setVisibility(View.GONE);
                    }

                    if (info.inviteLink != null && info.inviteLink.inviteLink != null) {
                        currentInviteLinkStr = info.inviteLink.inviteLink;
                        tvInviteLink.setText(currentInviteLinkStr);
                        cardInviteLink.setVisibility(View.VISIBLE);
                    } else {
                        createInviteLinkInternal();
                    }

                    String countLabel = isChannel ? " subscribers" : " members";
                    tvSubtitle.setText(info.memberCount + countLabel);
                    tvMembersCountHeader.setText((isChannel ? "Subscribers (" : "Members (") + info.memberCount + ")");

                    // Load supergroup members
                    loadSupergroupMembers();
                });
            }
        });
    }

    private void loadSupergroupMembers() {
        if (supergroupId == 0 || client == null) return;
        client.getSupergroupMembers(supergroupId, new TdApi.SupergroupMembersFilterRecent(), 0, 50, membersObj -> {
            if (membersObj instanceof TdApi.ChatMembers) {
                TdApi.ChatMembers chatMembers = (TdApi.ChatMembers) membersObj;
                runOnUiThread(() -> {
                    if (chatMembers.members != null && chatMembers.members.length > 0) {
                        memberAdapter.setMembers(Arrays.asList(chatMembers.members));
                        cardMembers.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void createInviteLinkInternal() {
        if (client == null || chatId == 0) return;
        client.createChatInviteLink(chatId, "Primary Invite Link", result -> {
            if (result instanceof TdApi.ChatInviteLink) {
                TdApi.ChatInviteLink link = (TdApi.ChatInviteLink) result;
                runOnUiThread(() -> {
                    currentInviteLinkStr = link.inviteLink;
                    tvInviteLink.setText(currentInviteLinkStr);
                    cardInviteLink.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void copyInviteLink() {
        if (currentInviteLinkStr == null || currentInviteLinkStr.isEmpty()) {
            Toast.makeText(this, "Invite link not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Telegram Invite Link", currentInviteLinkStr));
            Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareInviteLink() {
        if (currentInviteLinkStr == null || currentInviteLinkStr.isEmpty()) {
            Toast.makeText(this, "Invite link not available yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, currentInviteLinkStr);
        startActivity(Intent.createChooser(shareIntent, "Share Invite Link"));
    }

    private void showAddMemberPicker() {
        if (client == null) return;
        progressBar.setVisibility(View.VISIBLE);

        client.getContacts(object -> {
            runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            if (object instanceof TdApi.Users) {
                long[] userIds = ((TdApi.Users) object).userIds;
                if (userIds.length == 0) {
                    runOnUiThread(() -> Toast.makeText(this, "No contacts found to add", Toast.LENGTH_SHORT).show());
                    return;
                }
                loadContactsForSelection(userIds);
            }
        });
    }

    private void loadContactsForSelection(long[] userIds) {
        List<TdApi.User> contacts = new ArrayList<>();
        int total = userIds.length;
        for (long uid : userIds) {
            client.getUser(uid, obj -> {
                if (obj instanceof TdApi.User) {
                    synchronized (contacts) {
                        contacts.add((TdApi.User) obj);
                        if (contacts.size() == total) {
                            runOnUiThread(() -> showContactChoiceDialog(contacts));
                        }
                    }
                }
            });
        }
    }

    private void showContactChoiceDialog(List<TdApi.User> contacts) {
        String[] names = new String[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) {
            TdApi.User u = contacts.get(i);
            String name = (u.firstName + " " + (u.lastName != null ? u.lastName : "")).trim();
            if (name.isEmpty()) name = "User " + u.id;
            names[i] = name;
        }

        new AlertDialog.Builder(this)
                .setTitle("Add Member")
                .setItems(names, (dialog, which) -> {
                    TdApi.User chosen = contacts.get(which);
                    addMemberToChat(chosen);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addMemberToChat(TdApi.User user) {
        if (client == null || chatId == 0) return;
        progressBar.setVisibility(View.VISIBLE);

        client.addChatMember(chatId, user.id, result -> runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (result instanceof TdApi.Ok || result instanceof TdApi.FailedToAddMembers) {
                Toast.makeText(this, user.firstName + " added", Toast.LENGTH_SHORT).show();
                loadChatDetails();
            } else if (result instanceof TdApi.Error) {
                Toast.makeText(this, "Error: " + ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void showMemberOptionsDialog(TdApi.ChatMember member, TdApi.User user) {
        String name = user != null ? (user.firstName + " " + (user.lastName != null ? user.lastName : "")).trim() : "Member";
        String[] options = {"Open Chat", "Remove from " + (isChannel ? "channel" : "group")};

        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openPrivateChatWithUser(user != null ? user.id : 0);
                    } else if (which == 1) {
                        confirmRemoveMember(member, name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveMember(TdApi.ChatMember member, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove Member")
                .setMessage("Are you sure you want to remove " + name + " from the chat?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    long uid = member.memberId instanceof TdApi.MessageSenderUser
                            ? ((TdApi.MessageSenderUser) member.memberId).userId : 0;
                    if (uid != 0 && client != null) {
                        client.removeChatMember(chatId, uid, res -> runOnUiThread(() -> {
                            if (res instanceof TdApi.Ok) {
                                Toast.makeText(this, name + " removed", Toast.LENGTH_SHORT).show();
                                loadChatDetails();
                            } else if (res instanceof TdApi.Error) {
                                Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                            }
                        }));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPrivateChatWithUser(long userId) {
        if (userId == 0 || client == null) return;
        client.createPrivateChat(userId, res -> {
            if (res instanceof TdApi.Chat) {
                TdApi.Chat targetChat = (TdApi.Chat) res;
                runOnUiThread(() -> {
                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra("chat_id", targetChat.id);
                    intent.putExtra("chat_title", targetChat.title);
                    intent.putExtra("account_id", accountId);
                    startActivity(intent);
                });
            }
        });
    }

    private void confirmLeaveChat() {
        String actionName = isChannel ? "Leave Channel" : "Leave Group";
        new AlertDialog.Builder(this)
                .setTitle(actionName)
                .setMessage("Are you sure you want to leave this chat?")
                .setPositiveButton("Leave", (dialog, which) -> {
                    if (client != null && chatId != 0) {
                        client.leaveChat(chatId, res -> runOnUiThread(() -> {
                            Toast.makeText(this, "Left chat", Toast.LENGTH_SHORT).show();
                            finish();
                        }));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static String getInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    private static int getColorForId(long id) {
        int[] colors = {
                Color.parseColor("#E17076"),
                Color.parseColor("#FAA774"),
                Color.parseColor("#A695E7"),
                Color.parseColor("#7BC862"),
                Color.parseColor("#6EC9CB"),
                Color.parseColor("#65AADD"),
                Color.parseColor("#EE7AAE"),
                Color.parseColor("#2196F3")
        };
        return colors[(int) (Math.abs(id) % colors.length)];
    }

    private static String formatUserStatus(TdApi.UserStatus status) {
        if (status instanceof TdApi.UserStatusOnline) {
            return "online";
        } else if (status instanceof TdApi.UserStatusOffline) {
            return "last seen recently";
        } else if (status instanceof TdApi.UserStatusRecently) {
            return "last seen recently";
        } else if (status instanceof TdApi.UserStatusLastWeek) {
            return "last seen within a week";
        } else if (status instanceof TdApi.UserStatusLastMonth) {
            return "last seen within a month";
        }
        return "offline";
    }
}
