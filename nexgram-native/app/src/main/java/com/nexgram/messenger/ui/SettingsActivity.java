package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.google.android.material.textfield.TextInputLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import org.drinkless.tdlib.TdApi;

/**
 * Native Settings Activity
 *
 * Implements Milestone 2.5 — Account-Specific Proxy Manager:
 * - Independent proxy configurations for every Telegram account
 * - Direct, SOCKS5, and MTProto routing support
 * - Dynamic proxy switching after login WITHOUT logout, session loss, or OTP
 * - Live proxy verification via native TDLib PingProxy
 * - Encrypted credential isolation in Android Keystore via SecureSessionVault
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

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
        String targetId = active != null ? active.id : AuthActivity.ACCOUNT_ID;
        ProxyController.ProxyMetadata meta = (proxy != null) ? proxy.getProxyMetadata(targetId) : null;

        if (meta != null && meta.enabled && meta.type != ProxyController.ProxyType.DIRECT) {
            String latency = meta.lastLatencyMs > 0 ? " (" + meta.lastLatencyMs + " ms)" : "";
            tvProxyStatus.setText("Connected: " + meta.type + " " + meta.host + ":" + meta.port + latency);
        } else {
            tvProxyStatus.setText("Direct Connection (Proxy Disabled)");
        }
    }

    private void showProxyConfigurationDialog() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        String targetAccountId = active != null ? active.id : AuthActivity.ACCOUNT_ID;

        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        ProxyController.ProxyMetadata current = proxy != null ? proxy.getProxyMetadata(targetAccountId) : null;
        String currentSecret = proxy != null ? proxy.getProxySecret(targetAccountId) : "";

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        TdLibAccountClient client = tdManager != null ? tdManager.getClient(targetAccountId) : null;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_proxy_config, null);
        RadioGroup rgType = dialogView.findViewById(R.id.rgProxyType);
        RadioButton rbDirect = dialogView.findViewById(R.id.rbDirect);
        RadioButton rbSocks5 = dialogView.findViewById(R.id.rbSocks5);
        RadioButton rbMtproto = dialogView.findViewById(R.id.rbMtproto);

        View layoutFields = dialogView.findViewById(R.id.layoutProxyFields);
        TextInputLayout tilHost = dialogView.findViewById(R.id.tilProxyHost);
        EditText etHost = dialogView.findViewById(R.id.etProxyHost);
        TextInputLayout tilPort = dialogView.findViewById(R.id.tilProxyPort);
        EditText etPort = dialogView.findViewById(R.id.etProxyPort);
        TextInputLayout tilUsername = dialogView.findViewById(R.id.tilProxyUsername);
        EditText etUsername = dialogView.findViewById(R.id.etProxyUsername);
        TextInputLayout tilSecret = dialogView.findViewById(R.id.tilProxySecret);
        EditText etSecret = dialogView.findViewById(R.id.etProxySecret);

        Button btnTestPing = dialogView.findViewById(R.id.btnTestPing);
        ProgressBar pbTesting = dialogView.findViewById(R.id.pbTestingProxy);
        TextView tvPingResult = dialogView.findViewById(R.id.tvPingResult);

        // Prepopulate current configuration
        if (current == null || !current.enabled || current.type == ProxyController.ProxyType.DIRECT) {
            rbDirect.setChecked(true);
            layoutFields.setVisibility(View.GONE);
        } else {
            if (current.type == ProxyController.ProxyType.MTPROTO) {
                rbMtproto.setChecked(true);
                tilUsername.setVisibility(View.GONE);
                tilSecret.setHint("MTProto Secret Key (Hex)");
            } else {
                rbSocks5.setChecked(true);
                tilUsername.setVisibility(View.VISIBLE);
                tilSecret.setHint("Password (Optional)");
            }
            layoutFields.setVisibility(View.VISIBLE);
            if (current.host != null) etHost.setText(current.host);
            if (current.port > 0) etPort.setText(String.valueOf(current.port));
            if (current.username != null) etUsername.setText(current.username);
            if (currentSecret != null) etSecret.setText(currentSecret);
        }

        // Radio button change listener
        rgType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbDirect) {
                layoutFields.setVisibility(View.GONE);
            } else if (checkedId == R.id.rbMtproto) {
                layoutFields.setVisibility(View.VISIBLE);
                tilUsername.setVisibility(View.GONE);
                tilSecret.setHint("MTProto Secret Key (Hex)");
            } else {
                layoutFields.setVisibility(View.VISIBLE);
                tilUsername.setVisibility(View.VISIBLE);
                tilSecret.setHint("Password (Optional)");
            }
            tvPingResult.setText("");
        });

        // Test Ping button
        btnTestPing.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String user = etUsername.getText().toString().trim();
            String secret = etSecret.getText().toString().trim();
            int port;
            try {
                port = Integer.parseInt(portStr);
                ProxyController.validate(host, port);
            } catch (Exception e) {
                tvPingResult.setText(e.getMessage());
                tvPingResult.setTextColor(0xFFE53935);
                return;
            }

            ProxyController.ProxyMetadata testMeta = new ProxyController.ProxyMetadata();
            testMeta.host = host;
            testMeta.port = port;
            testMeta.username = user;
            testMeta.enabled = true;
            testMeta.type = rbMtproto.isChecked() ? ProxyController.ProxyType.MTPROTO : ProxyController.ProxyType.SOCKS5;

            TdApi.Proxy tdProxy = ProxyController.buildTdApiProxy(testMeta, secret);

            pbTesting.setVisibility(View.VISIBLE);
            tvPingResult.setText("Pinging through TDLib engine...");
            tvPingResult.setTextColor(0xFF7F91A4);

            if (client != null && tdProxy != null) {
                client.pingProxy(tdProxy, result -> {
                    runOnUiThread(() -> {
                        pbTesting.setVisibility(View.GONE);
                        if (result instanceof TdApi.Seconds) {
                            long ms = (long) (((TdApi.Seconds) result).seconds * 1000.0);
                            tvPingResult.setText("Success! Latency: " + ms + " ms");
                            tvPingResult.setTextColor(0xFF00C853);
                        } else if (result instanceof TdApi.Error) {
                            tvPingResult.setText("Ping failed: " + ((TdApi.Error) result).message);
                            tvPingResult.setTextColor(0xFFE53935);
                        }
                    });
                });
            } else {
                // Background socket reachability fallback
                new Thread(() -> {
                    ProxyController.ReachabilityResult res = proxy.testReachability(host, port, 4000);
                    runOnUiThread(() -> {
                        pbTesting.setVisibility(View.GONE);
                        if (res.status == ProxyController.ReachabilityStatus.TCP_REACHABLE) {
                            tvPingResult.setText("TCP Reachable (" + res.latencyMs + " ms)");
                            tvPingResult.setTextColor(0xFF00C853);
                        } else {
                            tvPingResult.setText(res.note);
                            tvPingResult.setTextColor(0xFFE53935);
                        }
                    });
                }).start();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Account Proxy Configuration")
                .setView(dialogView)
                .setPositiveButton("Save & Apply", (dialog, which) -> {
                    if (rbDirect.isChecked()) {
                        proxy.saveDirectConnection(targetAccountId);
                        if (client != null) {
                            client.disableProxy(null);
                        }
                        loadProfile();
                        Toast.makeText(this, "Direct connection applied (Proxy disabled)", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String host = etHost.getText().toString().trim();
                    String portStr = etPort.getText().toString().trim();
                    String user = etUsername.getText().toString().trim();
                    String secret = etSecret.getText().toString().trim();
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                        ProxyController.validate(host, port);
                    } catch (Exception e) {
                        Toast.makeText(this, "Validation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }

                    ProxyController.ProxyMetadata meta = new ProxyController.ProxyMetadata();
                    meta.host = host;
                    meta.port = port;
                    meta.username = user;
                    meta.enabled = true;
                    meta.type = rbMtproto.isChecked() ? ProxyController.ProxyType.MTPROTO : ProxyController.ProxyType.SOCKS5;

                    proxy.saveProxy(targetAccountId, meta, secret);

                    // Dynamically apply to active TDLib client without logging out or dropping session
                    if (client != null) {
                        TdApi.Proxy tdProxy = ProxyController.buildTdApiProxy(meta, secret);
                        client.setProxy(tdProxy, res -> {
                            Log.i(TAG, "TDLib SetProxy completed for " + targetAccountId + ": " + res);
                        });
                        Toast.makeText(this, "Proxy applied via TDLib. Session maintained (no logout needed).", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Proxy profile saved in Keystore for account: " + targetAccountId, Toast.LENGTH_SHORT).show();
                    }

                    loadProfile();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showBackupInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_backup)
                .setMessage("Hardware-Backed Keystore Vault: Active (AES-256-GCM)\n\n" +
                             "Account credentials, proxy secrets, and TDLib database encryption keys are isolated per account.")
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
