package com.apex.meshplayer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final int PERMISSION_REQUEST_CODE = 100;

    private WebView mWebView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private final Map<Long, JSONObject> mTrackDownloads = new ConcurrentHashMap<>();
    private BroadcastReceiver mDownloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupWindowDisplay();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#07070A"));
        applyEdgeToEdgeInsets(root);

        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.parseColor("#07070A"));
        root.addView(mWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);
        configureWebView();

        YouTubeExtractorHelper.init();

        // Register broadcast receiver to auto-notify WebView when a download finishes
        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (downloadId != -1) {
                        onDownloadCompleted(downloadId);
                    }
                }
            }
        };
        IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 14+ (Redmi / HyperOS on Android 15) throws SecurityException without an explicit flag.
            // DOWNLOAD_COMPLETE is sent by the DownloadProvider process, so the receiver must be exported.
            registerReceiver(mDownloadReceiver, dlFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mDownloadReceiver, dlFilter);
        }

        requestStoragePermissions();

        // Connect native MediaPlaybackService (notification in shade + headset controls)
        MediaPlaybackService.setListener(new MediaPlaybackService.PlaybackEventListener() {
            @Override
            public void onPlayRequested() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativePlay) window.onNativePlay(); else if (window.apexPlayer) window.apexPlayer.playAudio();", null);
                    }
                });
            }

            @Override
            public void onPauseRequested() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativePause) window.onNativePause(); else if (window.apexPlayer) window.apexPlayer.pauseAudio();", null);
                    }
                });
            }

            @Override
            public void onTogglePlayRequested() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativePlayPause) window.onNativePlayPause(); else if (window.apexPlayer) window.apexPlayer.togglePlayPause();", null);
                    }
                });
            }

            @Override
            public void onNextRequested() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativeNext) window.onNativeNext(); else if (window.apexPlayer) window.apexPlayer.playNext();", null);
                    }
                });
            }

            @Override
            public void onPrevRequested() {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativePrev) window.onNativePrev(); else if (window.apexPlayer) window.apexPlayer.playPrev();", null);
                    }
                });
            }

            @Override
            public void onSeekRequested(long positionMs) {
                runOnUiThread(() -> {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (window.onNativeSeek) window.onNativeSeek(" + (positionMs / 1000.0) + "); else if (window.apexPlayer) window.apexPlayer.seekAudio(" + (positionMs / 1000.0) + ");", null);
                    }
                });
            }
        });

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MediaPlaybackService.setListener(null);
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (Exception ignored) {}
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13-15: needed for reading music and showing playback notification in shade
            java.util.List<String> perms = new java.util.ArrayList<>();
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.READ_MEDIA_AUDIO);
            }
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needWrite = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean needRead = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (needWrite || needRead) {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                }, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && mWebView != null) {
            // Re-scan library now that we can see all audio files
            mWebView.evaluateJavascript("if (window.rescanLocalMusic) { window.rescanLocalMusic(); }", null);
        }
    }

    /**
     * Android 15 (targetSdk 35) forces edge-to-edge: status/navigation bar colors are ignored and the
     * WebView would be drawn under the bars. Pad the root view by system bar + cutout insets instead.
     * Android 10 (Huawei P20 Pro) keeps the original behaviour (CSS safe-area handles the notch).
     */
    private void applyEdgeToEdgeInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    private void onDownloadCompleted(long downloadId) {
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(downloadId);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                JSONObject meta = mTrackDownloads.get(downloadId);
                if (meta != null && meta.has("tmpPath")) {
                    mTrackDownloads.remove(downloadId);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        processTaggedDownload(meta);
                    } else {
                        new File(meta.optString("tmpPath")).delete();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Ошибка скачивания: " + meta.optString("title"), Toast.LENGTH_SHORT).show());
                    }
                    return;
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    String filename = (meta != null && meta.has("filename"))
                            ? meta.optString("filename")
                            : c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE));
                    String title = (meta != null && meta.has("title"))
                            ? meta.optString("title")
                            : filename;
                    String artist = (meta != null && meta.has("artist"))
                            ? meta.optString("artist")
                            : "YouTube Music";

                    File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File targetFile = new File(dlDir, filename);

                    if (!targetFile.exists()) {
                        String localUriStr = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                        if (localUriStr != null && localUriStr.startsWith("file://")) {
                            try {
                                targetFile = new File(Uri.parse(localUriStr).getPath());
                            } catch (Exception ignored) {}
                        }
                    }

                    if (targetFile.exists()) {
                        final String absPath = targetFile.getAbsolutePath();
                        final String finalTitle = title;
                        final String finalArtist = artist;
                        final String finalFileName = targetFile.getName();

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Скачано и добавлено в плеер: " + finalTitle, Toast.LENGTH_SHORT).show();
                            if (mWebView != null) {
                                try {
                                    JSONObject obj = new JSONObject();
                                    obj.put("title", finalTitle);
                                    obj.put("artist", finalArtist);
                                    obj.put("filename", finalFileName);
                                    obj.put("path", absPath);
                                    obj.put("url", "local-audio://" + absPath);
                                    obj.put("isLocal", true);
                                    obj.put("isDownloaded", true);
                                    obj.put("format", "MP3");

                                    String js = String.format("if (window.onAndroidTrackDownloaded) { window.onAndroidTrackDownloaded(%s); }",
                                            JSONObject.quote(obj.toString()));
                                    mWebView.evaluateJavascript(js, null);
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("ApexMusic", "onDownloadCompleted error", e);
        }
    }

    /** Background: embed cover + tags, publish to Downloads, then notify the WebView. */
    private void processTaggedDownload(final JSONObject meta) {
        new Thread(() -> {
            final String title = meta.optString("title", "Track");
            final String artist = meta.optString("artist", "YouTube");
            try {
                File tmp = new File(meta.optString("tmpPath"));
                CoverArtHelper.Result r = CoverArtHelper.processDownloadedTrack(
                        MainActivity.this, tmp, meta.optString("filename"), title, artist,
                        meta.optString("videoId", ""), meta.optString("thumbnail", ""));

                final File audio = r.audioFile;
                final JSONObject obj = new JSONObject();
                obj.put("title", title);
                obj.put("artist", artist);
                obj.put("filename", audio.getName());
                obj.put("path", audio.getAbsolutePath());
                obj.put("url", "local-audio://" + audio.getAbsolutePath());
                obj.put("isLocal", true);
                obj.put("isDownloaded", true);
                obj.put("format", audio.getName().toLowerCase().endsWith(".m4a") ? "M4A" : "MP3");
                if (r.coverFile != null) {
                    obj.put("cover", "local-audio://" + r.coverFile.getAbsolutePath() + "?v=" + r.coverFile.lastModified());
                } else if (!meta.optString("thumbnail").isEmpty()) {
                    obj.put("cover", meta.optString("thumbnail"));
                }

                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Скачано с обложкой: " + title, Toast.LENGTH_SHORT).show();
                    if (mWebView != null) {
                        String js = String.format("if (window.onAndroidTrackDownloaded) { window.onAndroidTrackDownloaded(%s); }",
                                JSONObject.quote(obj.toString()));
                        mWebView.evaluateJavascript(js, null);
                    }
                });
            } catch (Throwable t) {
                android.util.Log.e("ApexMusic", "processTaggedDownload error", t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ошибка сохранения: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void setupWindowDisplay() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#07070A"));
        window.setNavigationBarColor(Color.parseColor("#07070A"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        View decorView = window.getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }

    private void configureWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Native Android Bridge
        mWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        mWebView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype != null ? mimetype : "audio/mpeg");
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String filename = URLUtil.guessFileName(url, contentDisposition, "youtube_music.mp3");
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(MainActivity.this, "Скачивание в папку Загрузки: " + filename, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                } catch (Exception ignored) {}
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null) {
                    String scheme = uri.getScheme();
                    // Intercept local audio and files from device storage to bypass CORS and WebView file access restrictions
                    if ("local-audio".equalsIgnoreCase(scheme) || ("file".equalsIgnoreCase(scheme) && uri.getPath() != null && uri.getPath().startsWith("/storage/"))) {
                        try {
                            String path = uri.getPath();
                            if (path != null) {
                                path = Uri.decode(path);
                                if (!path.startsWith("/storage/") && uri.getAuthority() != null && !uri.getAuthority().isEmpty()) {
                                    path = "/" + uri.getAuthority() + path;
                                }
                                File file = new File(path);
                                if (!file.exists()) {
                                    File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                                    File alt = new File(dlDir, file.getName());
                                    if (alt.exists()) file = alt;
                                }
                                if (file.exists() && file.canRead()) {
                                    long totalLength = file.length();
                                    String lower = file.getName().toLowerCase();
                                    String mime = "audio/mpeg";
                                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mime = "image/jpeg";
                                    else if (lower.endsWith(".png")) mime = "image/png";
                                    else if (lower.endsWith(".m4a")) mime = "audio/mp4";
                                    else if (lower.endsWith(".wav")) mime = "audio/wav";
                                    else if (lower.endsWith(".flac")) mime = "audio/flac";
                                    else if (lower.endsWith(".ogg")) mime = "audio/ogg";

                                    Map<String, String> headers = new HashMap<>();
                                    headers.put("Access-Control-Allow-Origin", "*");
                                    headers.put("Accept-Ranges", "bytes");

                                    String range = request.getRequestHeaders() != null ? request.getRequestHeaders().get("Range") : null;
                                    if (range != null && range.startsWith("bytes=")) {
                                        String rangeSpec = range.substring(6).trim();
                                        long start = 0;
                                        long end = totalLength - 1;
                                        int dash = rangeSpec.indexOf('-');
                                        if (dash != -1) {
                                            String sStr = rangeSpec.substring(0, dash).trim();
                                            String eStr = rangeSpec.substring(dash + 1).trim();
                                            if (!sStr.isEmpty()) {
                                                try { start = Long.parseLong(sStr); } catch (Exception ignored) {}
                                            }
                                            if (!eStr.isEmpty()) {
                                                try { end = Long.parseLong(eStr); } catch (Exception ignored) {}
                                            }
                                        }
                                        if (end >= totalLength) end = totalLength - 1;
                                        long contentLength = Math.max(0, end - start + 1);

                                        FileInputStream fis = new FileInputStream(file);
                                        if (start > 0) {
                                            long skipped = fis.skip(start);
                                        }
                                        InputStream is = new LimitedInputStream(fis, contentLength);
                                        headers.put("Content-Range", "bytes " + start + "-" + end + "/" + totalLength);
                                        headers.put("Content-Length", String.valueOf(contentLength));
                                        return new WebResourceResponse(mime, null, 206, "Partial Content", headers, is);
                                    } else {
                                        headers.put("Content-Length", String.valueOf(totalLength));
                                        return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(file));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            android.util.Log.e("ApexMusic", "Intercept local audio error", e);
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                            FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                try {
                    startActivityForResult(Intent.createChooser(intent, "Выберите аудиофайлы или треки"), FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        mWebView.loadUrl("file:///android_asset/web/index.html");
    }

    public class AndroidBridge {

        @JavascriptInterface
        public void updatePlaybackState(boolean isPlaying, String title, String artist, String coverUrl, double positionSec, double durationSec) {
            long posMs = (long) (positionSec * 1000);
            long durMs = (long) (durationSec * 1000);
            MediaPlaybackService.updateState(MainActivity.this, isPlaying, title, artist, coverUrl, posMs, durMs);
        }

        @JavascriptInterface
        public void savePlayerState(String stateJson) {
            try {
                getSharedPreferences("apex_music_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("player_state", stateJson)
                        .apply();
            } catch (Exception e) {
                android.util.Log.e("ApexMusic", "savePlayerState error", e);
            }
        }

        @JavascriptInterface
        public String getSavedPlayerState() {
            try {
                return getSharedPreferences("apex_music_prefs", MODE_PRIVATE)
                        .getString("player_state", null);
            } catch (Exception e) {
                android.util.Log.e("ApexMusic", "getSavedPlayerState error", e);
                return null;
            }
        }

        @JavascriptInterface
        public void downloadAudio(String url, String filename, String title, String artist) {
            enqueueTaggedDownload(url, filename, title, artist, "", "");
        }

        /**
         * Downloads audio into app-private storage (no permissions needed on Android 10-15),
         * then {@link #processTaggedDownload} embeds title/artist/cover and moves it to Downloads.
         */
        void enqueueTaggedDownload(String url, String filename, String title, String artist,
                                   String videoId, String thumbnail) {
            runOnUiThread(() -> {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

                    String cleanName = (filename != null && !filename.isEmpty()) ? filename : "music_track.mp3";
                    cleanName = cleanName.replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (!cleanName.toLowerCase().endsWith(".m4a") && !cleanName.toLowerCase().endsWith(".mp3")) {
                        cleanName += ".mp3";
                    }
                    String ext = cleanName.toLowerCase().endsWith(".m4a") ? ".m4a" : ".mp3";
                    request.setMimeType(ext.equals(".mp3") ? "audio/mpeg" : "audio/mp4");

                    String finalTitle = title != null ? title : cleanName;
                    String finalArtist = artist != null ? artist : "YouTube";

                    String tmpName = "tmp_" + System.currentTimeMillis() + ext;
                    File tmpDir = getExternalFilesDir("tmp_downloads");
                    File tmpFile = new File(tmpDir, tmpName);

                    request.setTitle(finalTitle);
                    request.setDescription(finalArtist);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
                    request.setDestinationUri(Uri.fromFile(tmpFile));

                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        long downloadId = dm.enqueue(request);
                        JSONObject meta = new JSONObject();
                        meta.put("title", finalTitle);
                        meta.put("artist", finalArtist);
                        meta.put("filename", cleanName);
                        meta.put("tmpPath", tmpFile.getAbsolutePath());
                        meta.put("videoId", videoId != null ? videoId : "");
                        meta.put("thumbnail", thumbnail != null ? thumbnail : "");
                        mTrackDownloads.put(downloadId, meta);
                        Toast.makeText(MainActivity.this, "Скачивание: " + cleanName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ошибка скачивания: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void scanLocalDownloads(final String callbackId) {
            new Thread(() -> {
                try {
                    File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    JSONArray arr = new JSONArray();
                    if (dlDir != null && dlDir.exists() && dlDir.isDirectory()) {
                        File[] files = dlDir.listFiles((dir, name) -> {
                            String lower = name.toLowerCase();
                            return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg");
                        });
                        if (files != null) {
                            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (File f : files) {
                                String name = f.getName();
                                String title = name.replaceFirst("\\.[^.]+$", "");
                                String artist = "Загрузки телефона";
                                if (title.contains(" - ")) {
                                    String[] parts = title.split(" - ", 2);
                                    artist = parts[0].trim();
                                    title = parts[1].trim();
                                }
                                JSONObject item = new JSONObject();
                                File cover = CoverArtHelper.findOrExtractCover(MainActivity.this, f);
                                if (cover != null) {
                                    item.put("cover", "local-audio://" + cover.getAbsolutePath() + "?v=" + cover.lastModified());
                                }
                                item.put("title", title);
                                item.put("artist", artist);
                                item.put("filename", name);
                                item.put("path", f.getAbsolutePath());
                                item.put("url", "local-audio://" + f.getAbsolutePath());
                                item.put("isLocal", true);
                                item.put("isDownloaded", true);
                                item.put("format", name.substring(name.lastIndexOf('.') + 1).toUpperCase());
                                arr.put(item);
                            }
                        }
                    }
                    runOnUiThread(() -> {
                        if (mWebView != null) {
                            String js = String.format("if (window.onAndroidLocalFilesScanned) { window.onAndroidLocalFilesScanned(%s, %s); }",
                                    JSONObject.quote(callbackId),
                                    JSONObject.quote(arr.toString()));
                            mWebView.evaluateJavascript(js, null);
                        }
                    });
                } catch (Exception e) {
                    android.util.Log.e("ApexMusic", "scanLocalDownloads error", e);
                }
            }).start();
        }

        @JavascriptInterface
        public String getLocalAudioBase64(String path) {
            try {
                if (path == null) return null;
                if (path.startsWith("local-audio://")) {
                    path = path.substring("local-audio://".length());
                } else if (path.startsWith("file://")) {
                    path = path.substring("file://".length());
                }
                path = Uri.decode(path);
                if (path.contains("?")) {
                    path = path.substring(0, path.indexOf('?'));
                }
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
                File file = new File(path);
                if (!file.exists()) {
                    File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File alt = new File(dlDir, file.getName());
                    if (alt.exists()) file = alt;
                }
                if (file.exists() && file.canRead()) {
                    long len = file.length();
                    if (len > 35 * 1024 * 1024) { // safety limit 35MB
                        return null;
                    }
                    byte[] data = new byte[(int) len];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int read = 0;
                        while (read < data.length) {
                            int r = fis.read(data, read, data.length - read);
                            if (r == -1) break;
                            read += r;
                        }
                    }
                    return Base64.encodeToString(data, Base64.NO_WRAP);
                }
            } catch (Throwable t) {
                android.util.Log.e("ApexMusic", "getLocalAudioBase64 error", t);
            }
            return null;
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void searchYouTube(final String query, final String callbackId) {
            try {
                YouTubeExtractorHelper.searchOrResolveAsync(query, jsonResult -> {
                    runOnUiThread(() -> {
                        try {
                            if (mWebView != null) {
                                String js = String.format("if (window.onNativeYouTubeResult) { window.onNativeYouTubeResult(%s, %s); }",
                                        JSONObject.quote(callbackId),
                                        JSONObject.quote(jsonResult));
                                mWebView.evaluateJavascript(js, null);
                            }
                        } catch (Throwable t) {
                            android.util.Log.e("ApexMusic", "search callback error", t);
                        }
                    });
                });
            } catch (Throwable t) {
                android.util.Log.e("ApexMusic", "searchYouTube error", t);
            }
        }

        @JavascriptInterface
        public void getAudioStream(final String videoUrl, final String callbackId) {
            try {
                YouTubeExtractorHelper.getAudioStreamAsync(videoUrl, jsonResult -> {
                    runOnUiThread(() -> {
                        try {
                            if (mWebView != null) {
                                String js = String.format("if (window.onNativeYouTubeStreamResult) { window.onNativeYouTubeStreamResult(%s, %s); }",
                                        JSONObject.quote(callbackId),
                                        JSONObject.quote(jsonResult));
                                mWebView.evaluateJavascript(js, null);
                            }
                        } catch (Throwable t) {
                            android.util.Log.e("ApexMusic", "stream callback error", t);
                        }
                    });
                });
            } catch (Throwable t) {
                android.util.Log.e("ApexMusic", "getAudioStream error", t);
            }
        }

        @JavascriptInterface
        public void downloadMp3Async(final String videoId, final String title, final String artist, final String callbackId) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Подготовка к скачиванию MP3: " + title, Toast.LENGTH_SHORT).show());
            try {
                YouTubeExtractorHelper.requestMp3DownloadAsync(videoId, jsonResult -> {
                    runOnUiThread(() -> {
                        try {
                            JSONObject res = new JSONObject(jsonResult);
                            if (res.optBoolean("success", false)) {
                                String downloadUrl = res.optString("downloadUrl", "");
                                String format = res.optString("format", "MP3");
                                String ext = format.equalsIgnoreCase("M4A") ? ".m4a" : ".mp3";
                                if (!downloadUrl.isEmpty()) {
                                    String cleanArtist = CoverArtHelper.cleanArtist(artist);
                                    String cleanName = ((cleanArtist != null ? cleanArtist + " - " : "") + title + ext).replaceAll("[\\\\/:*?\"<>|]", "_");
                                    enqueueTaggedDownload(downloadUrl, cleanName, title, cleanArtist, videoId,
                                            "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
                                }
                            } else {
                                Toast.makeText(MainActivity.this, "Ошибка конвертации: " + res.optString("error", "Неизвестно"), Toast.LENGTH_SHORT).show();
                            }
                            if (mWebView != null) {
                                String js = String.format("if (window.onNativeMp3DownloadResult) { window.onNativeMp3DownloadResult(%s, %s); }",
                                        JSONObject.quote(callbackId),
                                        JSONObject.quote(jsonResult));
                                mWebView.evaluateJavascript(js, null);
                            }
                        } catch (Throwable t) {
                            android.util.Log.e("ApexMusic", "mp3 download callback error", t);
                        }
                    });
                });
            } catch (Throwable t) {
                android.util.Log.e("ApexMusic", "downloadMp3Async error", t);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action != null && mWebView != null) {
            if (MediaPlaybackService.ACTION_PLAY.equals(action)) {
                mWebView.post(() -> mWebView.evaluateJavascript("if (window.onNativePlay) window.onNativePlay(); else if (window.apexPlayer) window.apexPlayer.playAudio();", null));
            } else if (MediaPlaybackService.ACTION_PAUSE.equals(action)) {
                mWebView.post(() -> mWebView.evaluateJavascript("if (window.onNativePause) window.onNativePause(); else if (window.apexPlayer) window.apexPlayer.pauseAudio();", null));
            } else if (MediaPlaybackService.ACTION_TOGGLE_PLAY.equals(action)) {
                mWebView.post(() -> mWebView.evaluateJavascript("if (window.onNativePlayPause) window.onNativePlayPause(); else if (window.apexPlayer) window.apexPlayer.togglePlayPause();", null));
            } else if (MediaPlaybackService.ACTION_NEXT.equals(action)) {
                mWebView.post(() -> mWebView.evaluateJavascript("if (window.onNativeNext) window.onNativeNext(); else if (window.apexPlayer) window.apexPlayer.playNext();", null));
            } else if (MediaPlaybackService.ACTION_PREV.equals(action)) {
                mWebView.post(() -> mWebView.evaluateJavascript("if (window.onNativePrev) window.onNativePrev(); else if (window.apexPlayer) window.apexPlayer.playPrev();", null));
            }
        }

        Uri data = intent.getData();
        if (data != null && mWebView != null) {
            String uriString = data.toString();
            mWebView.post(() -> {
                mWebView.evaluateJavascript("if (window.handleAndroidAudioUri) { window.handleAndroidAudioUri('" + uriString + "'); }", null);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(
                "(function() { if (window.handleBackButton) { return window.handleBackButton(); } return false; })()",
                value -> {
                    if (!"true".equals(value)) {
                        super.onBackPressed();
                    }
                }
            );
        } else {
            super.onBackPressed();
        }
    }

    private static class LimitedInputStream extends FilterInputStream {
        private long remaining;

        public LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int res = super.read();
            if (res != -1) remaining--;
            return res;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int res = super.read(b, off, toRead);
            if (res != -1) remaining -= res;
            return res;
        }
    }
}
