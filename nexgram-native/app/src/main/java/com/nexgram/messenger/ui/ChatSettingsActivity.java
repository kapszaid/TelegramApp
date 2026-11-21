package com.nexgram.messenger.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.slider.Slider;
import com.nexgram.messenger.R;

public class ChatSettingsActivity extends AppCompatActivity {
    public static final String PREFS_NAME = "nexgram_chat_settings_prefs";
    public static final String KEY_TEXT_SIZE = "message_font_size";
    public static final String KEY_THEME_MODE = "app_theme_mode";

    private Toolbar toolbar;
    private TextView tvPreviewIncoming;
    private TextView tvPreviewOutgoing;
    private TextView tvFontSizeValue;
    private Slider sliderTextSize;

    private RadioGroup rgThemeMode;
    private RadioButton rbThemeSystem;
    private RadioButton rbThemeDark;
    private RadioButton rbThemeLight;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_settings);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        loadSavedSettings();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarChatSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvPreviewIncoming = findViewById(R.id.tvPreviewIncoming);
        tvPreviewOutgoing = findViewById(R.id.tvPreviewOutgoing);
        tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        sliderTextSize = findViewById(R.id.sliderTextSize);

        rgThemeMode = findViewById(R.id.rgThemeMode);
        rbThemeSystem = findViewById(R.id.rbThemeSystem);
        rbThemeDark = findViewById(R.id.rbThemeDark);
        rbThemeLight = findViewById(R.id.rbThemeLight);

        sliderTextSize.addOnChangeListener((slider, value, fromUser) -> {
            int size = (int) value;
            tvFontSizeValue.setText(size + " sp");
            tvPreviewIncoming.setTextSize(size);
            tvPreviewOutgoing.setTextSize(size);
            if (fromUser) {
                prefs.edit().putInt(KEY_TEXT_SIZE, size).apply();
            }
        });

        rgThemeMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode;
            if (checkedId == R.id.rbThemeDark) {
                mode = AppCompatDelegate.MODE_NIGHT_YES;
            } else if (checkedId == R.id.rbThemeLight) {
                mode = AppCompatDelegate.MODE_NIGHT_NO;
            } else {
                mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
            AppCompatDelegate.setDefaultNightMode(mode);
        });
    }

    private void loadSavedSettings() {
        int textSize = prefs.getInt(KEY_TEXT_SIZE, 16);
        sliderTextSize.setValue(textSize);
        tvFontSizeValue.setText(textSize + " sp");
        tvPreviewIncoming.setTextSize(textSize);
        tvPreviewOutgoing.setTextSize(textSize);

        int themeMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (themeMode == AppCompatDelegate.MODE_NIGHT_YES) {
            rbThemeDark.setChecked(true);
        } else if (themeMode == AppCompatDelegate.MODE_NIGHT_NO) {
            rbThemeLight.setChecked(true);
        } else {
            rbThemeSystem.setChecked(true);
        }
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
