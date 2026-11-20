package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;

/**
 * Native Settings Activity
 *
 * ACCURATE STATUS REPORTING & NO PLACEHOLDER SUCCESS NOTIFICATIONS:
 * 1. Does NOT report backup export success unless a physical file was created and written.
 * 2. Does NOT auto-configure loopback (127.0.0.1) as a fake proxy.
 * 3. Clearly labels protocol and file integrations that await MTProto engine connection.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvSettingsUser, tvSettingsPhone, tvProxyStatus;
    private Button btnLogout;
    private CardView cardAccountProfile, cardProxy, cardBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        tvSettingsUser = findViewById(R.id.tvSettingsUser);
        tvSettingsPhone = findViewById(R.id.tvSettingsPhone);
        tvProxyStatus = findViewById(R.id.tvProxyStatus);
        cardAccountProfile = findViewById(R.id.cardAccountProfile);
        cardProxy = findViewById(R.id.cardProxySettings);
        cardBackup = findViewById(R.id.cardBackup);
        btnLogout = findViewById(R.id.btnSettingsLogout);

        loadProfile();

        if (cardAccountProfile != null) {
            cardAccountProfile.setOnClickListener(v -> {
                AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                if (manager == null || manager.getActiveAccount() == null) {
                    startActivity(new Intent(SettingsActivity.this, AuthActivity.class));
                }
            });
        }

        cardProxy.setOnClickListener(v -> showProxyConfigurationDialog());
        cardBackup.setOnClickListener(v -> showBackupInfoDialog());
        btnLogout.setOnClickListener(v -> confirmLogout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfile();
    }

    private void loadProfile() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active != null) {
            tvSettingsUser.setText(active.name);
            tvSettingsPhone.setText(active.phone);
            btnLogout.setVisibility(View.VISIBLE);
        } else {
            tvSettingsUser.setText("No Active User");
            tvSettingsPhone.setText("Tap to log in with Telegram");
            btnLogout.setVisibility(View.GONE);
        }

        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        ProxyController.ProxyMetadata meta = (active != null && proxy != null) ? proxy.getProxyMetadata(active.id) : null;
        if (meta != null && meta.enabled) {
            tvProxyStatus.setText("Enabled: " + meta.host + ":" + meta.port + " (" + meta.type + ")");
        } else {
            tvProxyStatus.setText(R.string.proxy_disabled);
        }
    }

    private void showProxyConfigurationDialog() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Account Required")
                    .setMessage("Authorize an account before setting up a dedicated proxy profile.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        ProxyController.ProxyMetadata current = proxy.getProxyMetadata(active.id);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_proxy_config, null);
        EditText etHost = dialogView.findViewById(R.id.etProxyHost);
        EditText etPort = dialogView.findViewById(R.id.etProxyPort);
        EditText etSecret = dialogView.findViewById(R.id.etProxySecret);
        RadioGroup rgType = dialogView.findViewById(R.id.rgProxyType);

        if (current != null) {
            etHost.setText(current.host);
            etPort.setText(String.valueOf(current.port));
            if (current.type == ProxyController.ProxyType.MTPROTO) {
                rgType.check(R.id.rbMtproto);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Configure Proxy")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String host = etHost.getText().toString().trim();
                    String portStr = etPort.getText().toString().trim();
                    String secret = etSecret.getText().toString().trim();
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        ProxyController.validate(host, port);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }

                    ProxyController.ProxyMetadata meta = new ProxyController.ProxyMetadata();
                    meta.host = host;
                    meta.port = port;
                    meta.type = rgType.getCheckedRadioButtonId() == R.id.rbMtproto
                            ? ProxyController.ProxyType.MTPROTO
                            : ProxyController.ProxyType.SOCKS5;
                    meta.enabled = true;

                    proxy.saveProxy(active.id, meta, secret);
                    loadProfile();
                    Toast.makeText(this, "Proxy profile saved. (Native socket routing will activate with MTProto engine)", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton(current != null && current.enabled ? "Disable" : null, (dialog, which) -> {
                    proxy.setProxyEnabled(active.id, false);
                    loadProfile();
                    Toast.makeText(this, "Proxy disabled", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBackupInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_backup)
                .setMessage("Cryptographic Engine Status: Ready (AES-256-GCM + PBKDF2)\n\n" +
                             "Android Document Storage Export: Pending live MTProto account session authorization.\n\n" +
                             "When authorized accounts exist, this feature generates an encrypted archive exportable to Google Drive or local storage.")
                .setPositiveButton("Understood", null)
                .show();
    }

    private void confirmLogout() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active == null) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Log Out?")
                .setMessage("Are you sure you want to log out from " + active.name + "? All authentication tokens will be securely wiped from the Android Keystore.")
                .setPositiveButton("Log Out", (dialog, which) -> {
                    TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
                    if (tdManager != null) {
                        TdLibAccountClient client = tdManager.getClient(active.id);
                        if (client != null) {
                            client.logOut(result -> {
                                android.util.Log.i("SettingsActivity", "TDLib logOut finished: " + result);
                                client.deleteDatabaseFiles();
                            });
                        }
                        tdManager.closeClient(active.id);
                    }
                    manager.removeAccount(active.id);
                    Toast.makeText(this, "Logged out. Session wiped from Keystore.", Toast.LENGTH_SHORT).show();
                    loadProfile();
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
}
