package com.nexgram.messenger.ui.helpers;

import android.app.Activity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.textfield.TextInputLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import java.util.ArrayList;
import java.util.List;
import org.drinkless.tdlib.TdApi;

public class ProxyDialogHelper {
    private static final String TAG = "ProxyDialogHelper";

    public static void showProxyDialog(Activity activity, String targetAccountId, Runnable onConfigChanged) {
        if (activity == null || activity.isFinishing()) return;

        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        if (proxy == null) return;

        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        TdLibAccountClient client = tdManager != null ? tdManager.getClient(targetAccountId) : null;

        ProxyController.ProxyMetadata current = proxy.getProxyMetadata(targetAccountId);
        String currentSecret = proxy.getProxySecret(targetAccountId);

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_proxy_config, null);

        RadioGroup rgRoutingMode = dialogView.findViewById(R.id.rgRoutingMode);
        RadioButton rbModeDirect = dialogView.findViewById(R.id.rbModeDirect);
        RadioButton rbModeSaved = dialogView.findViewById(R.id.rbModeSaved);
        RadioButton rbModeCustom = dialogView.findViewById(R.id.rbModeCustom);

        // Saved proxies container
        View layoutSavedContainer = dialogView.findViewById(R.id.layoutSavedProxiesContainer);
        Spinner spSavedProxies = dialogView.findViewById(R.id.spSavedProxies);
        TextView tvSavedProxyInfo = dialogView.findViewById(R.id.tvSavedProxyInfo);
        Button btnDeleteSavedProxy = dialogView.findViewById(R.id.btnDeleteSavedProxy);

        // Custom proxy container
        View layoutCustomContainer = dialogView.findViewById(R.id.layoutCustomProxyContainer);
        TextInputLayout tilProxyName = dialogView.findViewById(R.id.tilProxyName);
        EditText etProxyName = dialogView.findViewById(R.id.etProxyName);
        RadioGroup rgCustomProxyType = dialogView.findViewById(R.id.rgCustomProxyType);
        RadioButton rbCustomSocks5 = dialogView.findViewById(R.id.rbCustomSocks5);
        RadioButton rbCustomMtproto = dialogView.findViewById(R.id.rbCustomMtproto);
        TextInputLayout tilProxyHost = dialogView.findViewById(R.id.tilProxyHost);
        EditText etProxyHost = dialogView.findViewById(R.id.etProxyHost);
        TextInputLayout tilProxyPort = dialogView.findViewById(R.id.tilProxyPort);
        EditText etProxyPort = dialogView.findViewById(R.id.etProxyPort);
        TextInputLayout tilProxyUsername = dialogView.findViewById(R.id.tilProxyUsername);
        EditText etProxyUsername = dialogView.findViewById(R.id.etProxyUsername);
        TextInputLayout tilProxySecret = dialogView.findViewById(R.id.tilProxySecret);
        EditText etProxySecret = dialogView.findViewById(R.id.etProxySecret);
        CheckBox cbSaveToLibrary = dialogView.findViewById(R.id.cbSaveToLibrary);

        // Ping test row
        View layoutTestPingRow = dialogView.findViewById(R.id.layoutTestPingRow);
        Button btnTestPing = dialogView.findViewById(R.id.btnTestPing);
        ProgressBar pbTesting = dialogView.findViewById(R.id.pbTestingProxy);
        TextView tvPingResult = dialogView.findViewById(R.id.tvPingResult);

        // Load saved proxies from global library
        List<ProxyController.SavedProxy> savedList = new ArrayList<>(proxy.getAllSavedProxies());
        ArrayAdapter<ProxyController.SavedProxy> spinnerAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, savedList);
        spSavedProxies.setAdapter(spinnerAdapter);

        // Helper to refresh spinner
        Runnable refreshSavedList = () -> {
            savedList.clear();
            savedList.addAll(proxy.getAllSavedProxies());
            spinnerAdapter.notifyDataSetChanged();
            if (savedList.isEmpty()) {
                tvSavedProxyInfo.setText("No saved proxies found. Switch to 'Add New Custom Proxy' to save one.");
                btnDeleteSavedProxy.setVisibility(View.GONE);
            } else {
                btnDeleteSavedProxy.setVisibility(View.VISIBLE);
            }
        };

        spSavedProxies.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < savedList.size()) {
                    ProxyController.SavedProxy sp = savedList.get(position);
                    String userPart = sp.username != null && !sp.username.isEmpty() ? " | User: " + sp.username : "";
                    tvSavedProxyInfo.setText("Protocol: " + sp.type + " | Host: " + sp.host + ":" + sp.port + userPart);
                    btnDeleteSavedProxy.setVisibility(View.VISIBLE);
                } else {
                    tvSavedProxyInfo.setText("No saved proxy selected");
                    btnDeleteSavedProxy.setVisibility(View.GONE);
                }
                tvPingResult.setText("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                btnDeleteSavedProxy.setVisibility(View.GONE);
            }
        });

        btnDeleteSavedProxy.setOnClickListener(v -> {
            int pos = spSavedProxies.getSelectedItemPosition();
            if (pos >= 0 && pos < savedList.size()) {
                ProxyController.SavedProxy toDel = savedList.get(pos);
                proxy.deleteSavedProxy(toDel.id);
                Toast.makeText(activity, "Deleted " + toDel.name, Toast.LENGTH_SHORT).show();
                refreshSavedList.run();
                if (savedList.isEmpty()) {
                    rbModeDirect.setChecked(true);
                }
            }
        });

        // Toggle custom proxy type
        rgCustomProxyType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbCustomMtproto) {
                tilProxyUsername.setVisibility(View.GONE);
                tilProxySecret.setHint("MTProto Secret Key (Hex)");
            } else {
                tilProxyUsername.setVisibility(View.VISIBLE);
                tilProxySecret.setHint("Password (Optional)");
            }
            tvPingResult.setText("");
        });

        // Toggle routing modes: Direct, Saved, Custom
        rgRoutingMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbModeDirect) {
                layoutSavedContainer.setVisibility(View.GONE);
                layoutCustomContainer.setVisibility(View.GONE);
                layoutTestPingRow.setVisibility(View.GONE);
            } else if (checkedId == R.id.rbModeSaved) {
                layoutSavedContainer.setVisibility(View.VISIBLE);
                layoutCustomContainer.setVisibility(View.GONE);
                layoutTestPingRow.setVisibility(View.VISIBLE);
                refreshSavedList.run();
            } else if (checkedId == R.id.rbModeCustom) {
                layoutSavedContainer.setVisibility(View.GONE);
                layoutCustomContainer.setVisibility(View.VISIBLE);
                layoutTestPingRow.setVisibility(View.VISIBLE);
            }
            tvPingResult.setText("");
        });

        // Initialize state based on current configuration
        if (current == null || !current.enabled || current.type == ProxyController.ProxyType.DIRECT) {
            rbModeDirect.setChecked(true);
            layoutSavedContainer.setVisibility(View.GONE);
            layoutCustomContainer.setVisibility(View.GONE);
            layoutTestPingRow.setVisibility(View.GONE);
        } else {
            // Check if current matches a saved proxy
            int matchedIndex = -1;
            for (int i = 0; i < savedList.size(); i++) {
                ProxyController.SavedProxy sp = savedList.get(i);
                if (sp.host.equalsIgnoreCase(current.host) && sp.port == current.port && sp.type == current.type) {
                    matchedIndex = i;
                    break;
                }
            }

            if (matchedIndex >= 0) {
                rbModeSaved.setChecked(true);
                spSavedProxies.setSelection(matchedIndex);
                layoutSavedContainer.setVisibility(View.VISIBLE);
                layoutCustomContainer.setVisibility(View.GONE);
                layoutTestPingRow.setVisibility(View.VISIBLE);
            } else {
                rbModeCustom.setChecked(true);
                layoutSavedContainer.setVisibility(View.GONE);
                layoutCustomContainer.setVisibility(View.VISIBLE);
                layoutTestPingRow.setVisibility(View.VISIBLE);
                if (current.type == ProxyController.ProxyType.MTPROTO) {
                    rbCustomMtproto.setChecked(true);
                    tilProxyUsername.setVisibility(View.GONE);
                    tilProxySecret.setHint("MTProto Secret Key (Hex)");
                } else {
                    rbCustomSocks5.setChecked(true);
                    tilProxyUsername.setVisibility(View.VISIBLE);
                    tilProxySecret.setHint("Password (Optional)");
                }
                if (current.host != null) etProxyHost.setText(current.host);
                if (current.port > 0) etProxyPort.setText(String.valueOf(current.port));
                if (current.username != null) etProxyUsername.setText(current.username);
                if (currentSecret != null) etProxySecret.setText(currentSecret);
            }
        }

        // Test Ping button logic
        btnTestPing.setOnClickListener(v -> {
            String host;
            int port;
            String user;
            String secret;
            ProxyController.ProxyType type;

            if (rbModeSaved.isChecked()) {
                int pos = spSavedProxies.getSelectedItemPosition();
                if (pos < 0 || pos >= savedList.size()) {
                    tvPingResult.setText("No saved proxy selected");
                    tvPingResult.setTextColor(0xFFE53935);
                    return;
                }
                ProxyController.SavedProxy sp = savedList.get(pos);
                host = sp.host;
                port = sp.port;
                user = sp.username;
                secret = proxy.getSavedProxySecret(sp.id);
                type = sp.type;
            } else {
                host = etProxyHost.getText().toString().trim();
                String portStr = etProxyPort.getText().toString().trim();
                user = etProxyUsername.getText().toString().trim();
                secret = etProxySecret.getText().toString().trim();
                type = rbCustomMtproto.isChecked() ? ProxyController.ProxyType.MTPROTO : ProxyController.ProxyType.SOCKS5;
                try {
                    port = Integer.parseInt(portStr);
                    ProxyController.validate(host, port);
                } catch (Exception e) {
                    tvPingResult.setText(e.getMessage());
                    tvPingResult.setTextColor(0xFFE53935);
                    return;
                }
            }

            ProxyController.ProxyMetadata testMeta = new ProxyController.ProxyMetadata();
            testMeta.host = host;
            testMeta.port = port;
            testMeta.username = user;
            testMeta.enabled = true;
            testMeta.type = type;

            TdApi.Proxy tdProxy = ProxyController.buildTdApiProxy(testMeta, secret);

            pbTesting.setVisibility(View.VISIBLE);
            tvPingResult.setText("Pinging proxy engine...");
            tvPingResult.setTextColor(0xFF7F91A4);

            if (client != null && tdProxy != null) {
                client.pingProxy(tdProxy, result -> {
                    activity.runOnUiThread(() -> {
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
                new Thread(() -> {
                    ProxyController.ReachabilityResult res = proxy.testReachability(host, port, 4000);
                    activity.runOnUiThread(() -> {
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

        new AlertDialog.Builder(activity)
                .setTitle("Account Proxy Configuration")
                .setView(dialogView)
                .setPositiveButton("Save & Apply", (dialog, which) -> {
                    if (rbModeDirect.isChecked()) {
                        proxy.saveDirectConnection(targetAccountId);
                        if (client != null) {
                            client.disableProxy(null);
                        }
                        Toast.makeText(activity, "Direct connection applied (Proxy disabled)", Toast.LENGTH_SHORT).show();
                        if (onConfigChanged != null) onConfigChanged.run();
                        return;
                    }

                    if (rbModeSaved.isChecked()) {
                        int pos = spSavedProxies.getSelectedItemPosition();
                        if (pos < 0 || pos >= savedList.size()) {
                            Toast.makeText(activity, "No saved proxy selected. Please select or add a proxy.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ProxyController.SavedProxy sp = savedList.get(pos);
                        proxy.applySavedProxyToAccount(targetAccountId, sp.id);

                        if (client != null) {
                            String secret = proxy.getSavedProxySecret(sp.id);
                            ProxyController.ProxyMetadata meta = proxy.getProxyMetadata(targetAccountId);
                            TdApi.Proxy tdProxy = ProxyController.buildTdApiProxy(meta, secret);
                            client.setProxy(tdProxy, res -> {
                                Log.i(TAG, "Saved proxy applied via TDLib: " + res);
                            });
                        }
                        Toast.makeText(activity, "Saved proxy applied: " + sp.name, Toast.LENGTH_SHORT).show();
                        if (onConfigChanged != null) onConfigChanged.run();
                        return;
                    }

                    if (rbModeCustom.isChecked()) {
                        String name = etProxyName.getText().toString().trim();
                        String host = etProxyHost.getText().toString().trim();
                        String portStr = etProxyPort.getText().toString().trim();
                        String user = etProxyUsername.getText().toString().trim();
                        String secret = etProxySecret.getText().toString().trim();
                        ProxyController.ProxyType type = rbCustomMtproto.isChecked() ? ProxyController.ProxyType.MTPROTO : ProxyController.ProxyType.SOCKS5;

                        int port;
                        try {
                            port = Integer.parseInt(portStr);
                            ProxyController.validate(host, port);
                        } catch (Exception e) {
                            Toast.makeText(activity, "Validation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            return;
                        }

                        // Save into global library if checkbox is checked
                        if (cbSaveToLibrary.isChecked()) {
                            proxy.saveToGlobalLibrary(name, type, host, port, user, secret);
                        }

                        ProxyController.ProxyMetadata meta = new ProxyController.ProxyMetadata();
                        meta.host = host;
                        meta.port = port;
                        meta.username = user;
                        meta.enabled = true;
                        meta.type = type;
                        meta.label = name.isEmpty() ? (type + " " + host + ":" + port) : name;

                        proxy.saveProxy(targetAccountId, meta, secret);

                        if (client != null) {
                            TdApi.Proxy tdProxy = ProxyController.buildTdApiProxy(meta, secret);
                            client.setProxy(tdProxy, res -> {
                                Log.i(TAG, "Custom proxy applied via TDLib: " + res);
                            });
                        }
                        Toast.makeText(activity, "Custom proxy saved & applied: " + meta.label, Toast.LENGTH_SHORT).show();
                        if (onConfigChanged != null) onConfigChanged.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
