package com.nexgram.messenger.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;

import org.drinkless.tdlib.TdApi;

public class PrivacySettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "nexgram_privacy_prefs";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private LinearLayout rowPhone;
    private TextView tvPhoneValue;

    private LinearLayout rowLastSeen;
    private TextView tvLastSeenValue;

    private LinearLayout rowProfilePhotos;
    private TextView tvProfilePhotosValue;

    private LinearLayout rowGroups;
    private TextView tvGroupsValue;

    private LinearLayout rowPasscode;
    private TextView tvPasscodeStatus;

    private LinearLayout row2FA;
    private ProgressBar progressBar;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_privacy_settings);

        accountId = getIntent().getStringExtra("account_id");
        resolveAccountClient();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        loadPrivacySettings();
    }

    private void resolveAccountClient() {
        if (accountId == null || accountId.isEmpty()) {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null) {
                accountId = active.id;
            }
        }
        if (accountId != null) {
            client = TdLibManager.getInstance().getClient(accountId);
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarPrivacy);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rowPhone = findViewById(R.id.rowPrivacyPhone);
        tvPhoneValue = findViewById(R.id.tvPrivacyPhoneValue);

        rowLastSeen = findViewById(R.id.rowPrivacyLastSeen);
        tvLastSeenValue = findViewById(R.id.tvPrivacyLastSeenValue);

        rowProfilePhotos = findViewById(R.id.rowPrivacyProfilePhotos);
        tvProfilePhotosValue = findViewById(R.id.tvPrivacyProfilePhotosValue);

        rowGroups = findViewById(R.id.rowPrivacyGroups);
        tvGroupsValue = findViewById(R.id.tvPrivacyGroupsValue);

        rowPasscode = findViewById(R.id.rowSecurityPasscode);
        tvPasscodeStatus = findViewById(R.id.tvPasscodeLockStatus);

        row2FA = findViewById(R.id.rowSecurity2FA);
        progressBar = findViewById(R.id.pbPrivacy);

        rowPhone.setOnClickListener(v -> showPrivacyChoiceDialog("Phone Number", new TdApi.UserPrivacySettingShowPhoneNumber(), tvPhoneValue));
        rowLastSeen.setOnClickListener(v -> showPrivacyChoiceDialog("Last Seen & Online", new TdApi.UserPrivacySettingShowStatus(), tvLastSeenValue));
        rowProfilePhotos.setOnClickListener(v -> showPrivacyChoiceDialog("Profile Photos", new TdApi.UserPrivacySettingShowProfilePhoto(), tvProfilePhotosValue));
        rowGroups.setOnClickListener(v -> showPrivacyChoiceDialog("Groups & Channels", new TdApi.UserPrivacySettingAllowChatInvites(), tvGroupsValue));

        rowPasscode.setOnClickListener(v -> togglePasscodeDialog());
        row2FA.setOnClickListener(v -> show2FAInfo());

        boolean hasPasscode = prefs.getString("app_passcode", null) != null;
        tvPasscodeStatus.setText(hasPasscode ? "On" : "Off");
    }

    private void loadPrivacySettings() {
        if (client == null) return;
        progressBar.setVisibility(View.VISIBLE);

        client.getUserPrivacySettingRules(new TdApi.UserPrivacySettingShowPhoneNumber(), res -> runOnUiThread(() -> {
            if (res instanceof TdApi.UserPrivacySettingRules) {
                tvPhoneValue.setText(formatRules((TdApi.UserPrivacySettingRules) res));
            }
        }));

        client.getUserPrivacySettingRules(new TdApi.UserPrivacySettingShowStatus(), res -> runOnUiThread(() -> {
            if (res instanceof TdApi.UserPrivacySettingRules) {
                tvLastSeenValue.setText(formatRules((TdApi.UserPrivacySettingRules) res));
            }
        }));

        client.getUserPrivacySettingRules(new TdApi.UserPrivacySettingShowProfilePhoto(), res -> runOnUiThread(() -> {
            if (res instanceof TdApi.UserPrivacySettingRules) {
                tvProfilePhotosValue.setText(formatRules((TdApi.UserPrivacySettingRules) res));
            }
        }));

        client.getUserPrivacySettingRules(new TdApi.UserPrivacySettingAllowChatInvites(), res -> runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (res instanceof TdApi.UserPrivacySettingRules) {
                tvGroupsValue.setText(formatRules((TdApi.UserPrivacySettingRules) res));
            }
        }));
    }

    private String formatRules(TdApi.UserPrivacySettingRules rules) {
        if (rules == null || rules.rules == null || rules.rules.length == 0) return "Everybody";
        for (TdApi.UserPrivacySettingRule rule : rules.rules) {
            if (rule instanceof TdApi.UserPrivacySettingRuleAllowAll) return "Everybody";
            if (rule instanceof TdApi.UserPrivacySettingRuleAllowContacts) return "My Contacts";
            if (rule instanceof TdApi.UserPrivacySettingRuleRestrictAll) return "Nobody";
        }
        return "Everybody";
    }

    private void showPrivacyChoiceDialog(String title, TdApi.UserPrivacySetting setting, TextView targetView) {
        String[] options = {"Everybody", "My Contacts", "Nobody"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    TdApi.UserPrivacySettingRule[] ruleArray;
                    if (which == 0) {
                        ruleArray = new TdApi.UserPrivacySettingRule[]{new TdApi.UserPrivacySettingRuleAllowAll()};
                    } else if (which == 1) {
                        ruleArray = new TdApi.UserPrivacySettingRule[]{new TdApi.UserPrivacySettingRuleAllowContacts()};
                    } else {
                        ruleArray = new TdApi.UserPrivacySettingRule[]{new TdApi.UserPrivacySettingRuleRestrictAll()};
                    }

                    progressBar.setVisibility(View.VISIBLE);
                    client.setUserPrivacySettingRules(setting, new TdApi.UserPrivacySettingRules(ruleArray), res -> runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (res instanceof TdApi.Ok) {
                            targetView.setText(options[which]);
                            Toast.makeText(this, title + " setting updated", Toast.LENGTH_SHORT).show();
                        } else if (res instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void togglePasscodeDialog() {
        String existing = prefs.getString("app_passcode", null);
        if (existing != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Disable Passcode")
                    .setMessage("Do you want to turn off the App Passcode lock?")
                    .setPositiveButton("Turn Off", (d, w) -> {
                        prefs.edit().remove("app_passcode").apply();
                        tvPasscodeStatus.setText("Off");
                        Toast.makeText(this, "Passcode lock disabled", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            final EditText input = new EditText(this);
            input.setHint("4-digit PIN");
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            new AlertDialog.Builder(this)
                    .setTitle("Set Passcode Lock")
                    .setMessage("Enter a 4-digit PIN to secure NexGram on this device.")
                    .setView(input)
                    .setPositiveButton("Set", (d, w) -> {
                        String pin = input.getText().toString().trim();
                        if (pin.length() >= 4) {
                            prefs.edit().putString("app_passcode", pin).apply();
                            tvPasscodeStatus.setText("On");
                            Toast.makeText(this, "Passcode lock enabled", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void show2FAInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Two-Step Verification")
                .setMessage("Two-step verification is managed directly through Telegram Cloud passwords. When signing in from a new device, Telegram will ask for your cloud password after SMS/Telegram verification code.")
                .setPositiveButton("Got it", null)
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
}
