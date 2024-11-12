package com.shizuri.vcs;

import android.os.*;
import android.view.View;
import android.widget.*;
import android.net.Uri;
import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.media.MediaMetadataRetriever;
import androidx.appcompat.app.AppCompatActivity;
import com.arthenica.mobileffmpeg.FFmpeg;

import java.io.*;

public class MainActivity extends AppCompatActivity {
    private VideoView videoView;
    private Button buttonSetThumbnail;
    private Uri videoUri;
    private MediaMetadataRetriever retriever;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 请求文件访问权限
        requestAllFilesAccessPermission();

        // 初始化视图元素
        videoView = findViewById(R.id.videoView);
        buttonSetThumbnail = findViewById(R.id.buttonSetThumbnail);

        // 获取传入的视频 URI
        videoUri = getIntent().getData();

        if (videoUri != null) {
            setupVideoView(videoUri);
        } else {
            // 1s 后关闭当前 Activity
            Toast.makeText(this, "未接收到视频文件", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(this::finish, 1000);
        }

        // 设置按钮点击事件
        buttonSetThumbnail.setOnClickListener(v -> {
            setThumbnailAsCover(videoUri);
        });
    }

    public void requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void setupVideoView(Uri uri) {
        videoView.setVideoURI(uri);

        // 如果大于3秒，预览前3秒
        videoView.seekTo(5000);
        videoView.pause(); // 暂停视频
        android.widget.MediaController mediaController = new android.widget.MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);

        // 初始化 MediaMetadataRetriever
        retriever = new MediaMetadataRetriever();
        retriever.setDataSource(this, uri);

    }

    private void setThumbnailAsCover(Uri uri) {
        File externalDir = getExternalFilesDir(null);
        if (retriever == null) {
            Toast.makeText(this, "无法获取视频信息", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int currentPosition = videoView.getCurrentPosition(); // 获取当前播放位置
            // 获取指定位置的帧
            Bitmap bitmap = retriever.getFrameAtTime(currentPosition * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);

            if (bitmap == null) {
                Toast.makeText(this, "无法获取当前帧", Toast.LENGTH_SHORT).show();
                return;
            }

            File coverFile = new File(externalDir, "cover.jpg");
            // 保存帧为图片
            try (FileOutputStream fos = new FileOutputStream(coverFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            }
            createVideoWithCover(uri, coverFile);



        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void createVideoWithCover(Uri uri, File coverFile) throws IOException {
        // 直接使用uri对应的原视频文件，并且新文件名为原文件名_with_cover.后缀
        System.out.println("uri: " + uri);
        // 将 URI 转为文件路径
        String videoPath = uri.getPath();
        System.out.println("videoPath: " + videoPath);
        String outputVideoPath = videoPath.replace(".mp4", "_with_cover.mp4");
        System.out.println("outputVideoPath: " + outputVideoPath);
        executeFFmpegCommands(new File(videoPath), coverFile, outputVideoPath);
    }

    private void executeFFmpegCommands(File inputVideoFile, File coverFile, String outputVideoPath) {
        String[] commands = {
                "-y",
                "-i", inputVideoFile.getAbsolutePath(),
                "-i", coverFile.getAbsolutePath(),
                "-map", "0",
                "-map", "1",
                "-map", "-0:2",
                "-c", "copy",
                "-disposition:v:1", "attached_pic",
                outputVideoPath
        };

        FFmpeg.executeAsync(commands, (executionId, returnCode) -> handleFFmpegResult(returnCode, inputVideoFile, coverFile, outputVideoPath));
    }

    private void handleFFmpegResult(int returnCode, File inputVideoFile, File coverFile, String outputVideoPath) {
        coverFile.delete();
        if (returnCode == 0) {
            Toast.makeText(this, "封面设置成功", Toast.LENGTH_SHORT).show();
            // 删除原视频文件
            inputVideoFile.delete();
            File newVideoFile = new File(outputVideoPath);
            // 重命名新文件为原文件名
            newVideoFile.renameTo(inputVideoFile);

            new Handler().postDelayed(this::finish, 700);
        } else {
            Toast.makeText(this, "封面设置失败", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (retriever != null) {
            try {
                retriever.release();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
