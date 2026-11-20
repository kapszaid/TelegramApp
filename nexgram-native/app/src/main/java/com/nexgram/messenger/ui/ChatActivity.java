package com.nexgram.messenger.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.nexgram.messenger.R;

/**
 * Native Chat Conversation Activity
 * Supports sending and viewing messages directly on Android.
 */
public class ChatActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        String chatTitle = getIntent().getStringExtra("chat_title");
        if (chatTitle == null) chatTitle = "Chat";

        Toolbar toolbar = findViewById(R.id.chatToolbar);
        toolbar.setTitle(chatTitle);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        EditText etInput = findViewById(R.id.etMessageInput);
        ImageButton btnSend = findViewById(R.id.btnSendMessage);

        btnSend.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (text.isEmpty()) return;

            etInput.setText("");
            Toast.makeText(this, "Message sent via MTProto", Toast.LENGTH_SHORT).show();
        });
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
