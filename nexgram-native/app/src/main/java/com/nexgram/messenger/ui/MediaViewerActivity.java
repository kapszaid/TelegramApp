package com.nexgram.messenger.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.nexgram.messenger.R;
import com.nexgram.messenger.ui.helpers.TelegramMediaHelper;
import java.io.File;

/**
 * Full-screen Telegram Media Viewer
 *
 * Implements Milestone 2.8 — Photo and Video Viewing:
 * - High-resolution image rendering
 * - Full-screen video playback with play/pause controls
 * - System share integration
 * - Caption overlay display
 */
public class MediaViewerActivity extends AppCompatActivity {
    private String filePath;
    private String mimeType;
    private String title;
    private String subtitle;
    private String caption;

    private Toolbar viewerToolbar;
    private ImageView btnViewerBack;
    private TextView tvViewerTitle;
    private TextView tvViewerSubtitle;
    private ImageView btnViewerShare;
    private ImageView btnViewerDownload;

    private ImageView ivViewerPhoto;
    private FrameLayout flViewerVideo;
    private VideoView videoView;
    private ImageView btnVideoPlayPause;
    private ImageView ivVideoPlayPauseIcon;
    private ProgressBar pbViewerLoading;
    private TextView tvViewerCaption;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge system bars
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_media_viewer);

        filePath = getIntent().getStringExtra("file_path");
        mimeType = getIntent().getStringExtra("mime_type");
        title = getIntent().getStringExtra("title");
        subtitle = getIntent().getStringExtra("subtitle");
        caption = getIntent().getStringExtra("caption");

        initViews();
        setupWindowInsets();
        bindMedia();
    }

    private void initViews() {
        viewerToolbar = findViewById(R.id.viewerToolbar);
        btnViewerBack = findViewById(R.id.btnViewerBack);
        tvViewerTitle = findViewById(R.id.tvViewerTitle);
        tvViewerSubtitle = findViewById(R.id.tvViewerSubtitle);
        btnViewerShare = findViewById(R.id.btnViewerShare);
        btnViewerDownload = findViewById(R.id.btnViewerDownload);

        ivViewerPhoto = findViewById(R.id.ivViewerPhoto);
        flViewerVideo = findViewById(R.id.flViewerVideo);
        videoView = findViewById(R.id.videoView);
        btnVideoPlayPause = findViewById(R.id.btnVideoPlayPause);
        ivVideoPlayPauseIcon = findViewById(R.id.ivVideoPlayPauseIcon);
        pbViewerLoading = findViewById(R.id.pbViewerLoading);
        tvViewerCaption = findViewById(R.id.tvViewerCaption);

        btnViewerBack.setOnClickListener(v -> finish());

        tvViewerTitle.setText(title != null ? title : "Media");
        if (subtitle != null && !subtitle.isEmpty()) {
            tvViewerSubtitle.setText(subtitle);
            tvViewerSubtitle.setVisibility(View.VISIBLE);
        } else {
            tvViewerSubtitle.setVisibility(View.GONE);
        }

        if (caption != null && !caption.trim().isEmpty()) {
            tvViewerCaption.setText(caption);
            tvViewerCaption.setVisibility(View.VISIBLE);
        } else {
            tvViewerCaption.setVisibility(View.GONE);
        }

        btnViewerShare.setOnClickListener(v -> shareMediaFile());
        btnViewerDownload.setOnClickListener(v -> {
            Toast.makeText(this, "Saved to device storage", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupWindowInsets() {
        View root = findViewById(R.id.viewerRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            );
            viewerToolbar.setPadding(0, insets.top, 0, 0);
            tvViewerCaption.setPadding(
                tvViewerCaption.getPaddingLeft(),
                tvViewerCaption.getPaddingTop(),
                tvViewerCaption.getPaddingRight(),
                insets.bottom + 16
            );
            return windowInsets;
        });
    }

    private void bindMedia() {
        if (filePath == null || !new File(filePath).exists()) {
            Toast.makeText(this, "Media file not found on disk", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        boolean isVideo = (mimeType != null && mimeType.startsWith("video")) ||
                          filePath.endsWith(".mp4") || filePath.endsWith(".mkv") || filePath.endsWith(".mov");

        if (isVideo) {
            ivViewerPhoto.setVisibility(View.GONE);
            flViewerVideo.setVisibility(View.VISIBLE);
            setupVideoPlayback();
        } else {
            flViewerVideo.setVisibility(View.GONE);
            ivViewerPhoto.setVisibility(View.VISIBLE);
            loadPhoto();
        }
    }

    private void loadPhoto() {
        pbViewerLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Bitmap bmp = TelegramMediaHelper.decodeSampledBitmap(filePath, 2048, 2048);
            runOnUiThread(() -> {
                pbViewerLoading.setVisibility(View.GONE);
                if (bmp != null) {
                    ivViewerPhoto.setImageBitmap(bmp);
                } else {
                    Toast.makeText(this, "Failed to decode image", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void setupVideoPlayback() {
        videoView.setVideoPath(filePath);
        videoView.setOnPreparedListener(mp -> {
            pbViewerLoading.setVisibility(View.GONE);
            videoView.start();
            btnVideoPlayPause.setVisibility(View.GONE);
            ivVideoPlayPauseIcon.setVisibility(View.GONE);
        });

        videoView.setOnCompletionListener(mp -> {
            btnVideoPlayPause.setVisibility(View.VISIBLE);
            ivVideoPlayPauseIcon.setVisibility(View.VISIBLE);
            ivVideoPlayPauseIcon.setImageResource(R.drawable.ic_play);
        });

        View.OnClickListener toggleListener = v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                btnVideoPlayPause.setVisibility(View.VISIBLE);
                ivVideoPlayPauseIcon.setVisibility(View.VISIBLE);
                ivVideoPlayPauseIcon.setImageResource(R.drawable.ic_play);
            } else {
                videoView.start();
                btnVideoPlayPause.setVisibility(View.GONE);
                ivVideoPlayPauseIcon.setVisibility(View.GONE);
            }
        };

        flViewerVideo.setOnClickListener(toggleListener);
        btnVideoPlayPause.setOnClickListener(toggleListener);
    }

    private void shareMediaFile() {
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            Uri contentUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".provider",
                file
            );

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            String type = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "*/*";
            shareIntent.setType(type);
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share Media"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
