package com.apex.meshplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Native Android Foreground Service & MediaSession provider.
 * Keeps playback active when screen is locked or app is in background,
 * displays persistent notification in notification shade with Play/Pause/Next/Prev,
 * and handles Bluetooth headset & wired earphone media button controls.
 */
public class MediaPlaybackService extends Service {

    private static final String TAG = "ApexMediaService";
    private static final String CHANNEL_ID = "apex_playback_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_UPDATE_STATE = "com.apex.meshplayer.action.UPDATE_STATE";
    public static final String ACTION_PLAY = "com.apex.meshplayer.action.PLAY";
    public static final String ACTION_PAUSE = "com.apex.meshplayer.action.PAUSE";
    public static final String ACTION_TOGGLE_PLAY = "com.apex.meshplayer.action.TOGGLE_PLAY";
    public static final String ACTION_NEXT = "com.apex.meshplayer.action.NEXT";
    public static final String ACTION_PREV = "com.apex.meshplayer.action.PREV";
    public static final String ACTION_SEEK = "com.apex.meshplayer.action.SEEK";
    public static final String ACTION_STOP = "com.apex.meshplayer.action.STOP";

    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_COVER_URL = "extra_cover_url";
    public static final String EXTRA_POSITION_MS = "extra_position_ms";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";
    public static final String EXTRA_SEEK_POS_MS = "extra_seek_pos_ms";

    public interface PlaybackEventListener {
        void onPlayRequested();
        void onPauseRequested();
        void onTogglePlayRequested();
        void onNextRequested();
        void onPrevRequested();
        void onSeekRequested(long positionMs);
    }

    private static volatile MediaPlaybackService sInstance = null;
    private static volatile PlaybackEventListener sListener = null;

    private MediaSession mMediaSession;
    private NotificationManager mNotificationManager;
    private PowerManager.WakeLock mWakeLock;
    private BroadcastReceiver mNoisyReceiver;

    private boolean mIsPlaying = false;
    private String mTitle = "Apex Music Player";
    private String mArtist = "Waiting for playback";
    private String mCoverUrl = null;
    private long mPositionMs = 0;
    private long mDurationMs = 0;
    private Bitmap mCoverBitmap = null;

    public static void setListener(PlaybackEventListener listener) {
        sListener = listener;
    }

    public static void updateState(Context context, boolean isPlaying, String title, String artist,
                                   String coverUrl, long positionMs, long durationMs) {
        MediaPlaybackService instance = sInstance;
        if (instance != null) {
            instance.applyStateUpdate(isPlaying, title, artist, coverUrl, positionMs, durationMs);
            return;
        }

        if (!isPlaying) {
            // Do not start foreground service if not actively playing
            return;
        }

        try {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(ACTION_UPDATE_STATE);
            intent.putExtra(EXTRA_IS_PLAYING, true);
            intent.putExtra(EXTRA_TITLE, title);
            intent.putExtra(EXTRA_ARTIST, artist);
            intent.putExtra(EXTRA_COVER_URL, coverUrl);
            intent.putExtra(EXTRA_POSITION_MS, positionMs);
            intent.putExtra(EXTRA_DURATION_MS, durationMs);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateState error: " + t.getMessage(), t);
        }
    }

    public static void handleMediaKeyEvent(Context context, KeyEvent keyEvent) {
        if (keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) return;
        int code = keyEvent.getKeyCode();
        PlaybackEventListener l = sListener;

        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (l != null) l.onPlayRequested();
                else sendActionToService(context, ACTION_PLAY);
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (l != null) l.onPauseRequested();
                else sendActionToService(context, ACTION_PAUSE);
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (l != null) l.onTogglePlayRequested();
                else sendActionToService(context, ACTION_TOGGLE_PLAY);
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (l != null) l.onNextRequested();
                else sendActionToService(context, ACTION_NEXT);
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (l != null) l.onPrevRequested();
                else sendActionToService(context, ACTION_PREV);
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (l != null) l.onPauseRequested();
                else sendActionToService(context, ACTION_STOP);
                break;
        }
    }

    private static void sendActionToService(Context context, String action) {
        MediaPlaybackService instance = sInstance;
        if (instance != null) {
            Intent intent = new Intent();
            intent.setAction(action);
            instance.handleAction(intent);
            return;
        }
        try {
            Intent i = new Intent(context, MediaPlaybackService.class);
            i.setAction(action);
            context.startService(i);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        initNotificationChannel();
        initMediaSession();
        initWakeLock();
        initNoisyReceiver();

        Log.i(TAG, "MediaPlaybackService created");
    }

    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Apex Music notification shade playback controls");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (mNotificationManager != null) {
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void initMediaSession() {
        mMediaSession = new MediaSession(this, "ApexMediaSession");
        mMediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent mbrPendingIntent = PendingIntent.getBroadcast(
                this, 0, mediaButtonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        mMediaSession.setMediaButtonReceiver(mbrPendingIntent);

        mMediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (sListener != null) sListener.onPlayRequested();
                else openActivityWithAction(ACTION_PLAY);
            }

            @Override
            public void onPause() {
                if (sListener != null) sListener.onPauseRequested();
                else openActivityWithAction(ACTION_PAUSE);
            }

            @Override
            public void onSkipToNext() {
                if (sListener != null) sListener.onNextRequested();
                else openActivityWithAction(ACTION_NEXT);
            }

            @Override
            public void onSkipToPrevious() {
                if (sListener != null) sListener.onPrevRequested();
                else openActivityWithAction(ACTION_PREV);
            }

            @Override
            public void onSeekTo(long pos) {
                if (sListener != null) sListener.onSeekRequested(pos);
            }

            @Override
            public void onStop() {
                if (sListener != null) sListener.onPauseRequested();
                stopPlaybackForeground();
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mediaButtonIntent != null && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                        handleMediaKeyEvent(MediaPlaybackService.this, event);
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });

        mMediaSession.setActive(true);
    }

    private void initWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApexMusic:PlaybackLock");
            mWakeLock.setReferenceCounted(false);
        }
    }

    private void initNoisyReceiver() {
        mNoisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    Log.i(TAG, "Audio becoming noisy: pausing playback");
                    if (sListener != null) sListener.onPauseRequested();
                }
            }
        };
        registerReceiver(mNoisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        handleAction(intent);
        return START_STICKY;
    }

    private void handleAction(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case ACTION_UPDATE_STATE:
                boolean playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, mIsPlaying);
                String title = intent.getStringExtra(EXTRA_TITLE);
                String artist = intent.getStringExtra(EXTRA_ARTIST);
                String coverUrl = intent.getStringExtra(EXTRA_COVER_URL);
                long posMs = intent.getLongExtra(EXTRA_POSITION_MS, mPositionMs);
                long durMs = intent.getLongExtra(EXTRA_DURATION_MS, mDurationMs);

                applyStateUpdate(playing, title, artist, coverUrl, posMs, durMs);
                break;

            case ACTION_PLAY:
                if (sListener != null) sListener.onPlayRequested();
                else openActivityWithAction(ACTION_PLAY);
                break;

            case ACTION_PAUSE:
                if (sListener != null) sListener.onPauseRequested();
                else openActivityWithAction(ACTION_PAUSE);
                break;

            case ACTION_TOGGLE_PLAY:
                if (sListener != null) sListener.onTogglePlayRequested();
                else openActivityWithAction(ACTION_TOGGLE_PLAY);
                break;

            case ACTION_NEXT:
                if (sListener != null) sListener.onNextRequested();
                else openActivityWithAction(ACTION_NEXT);
                break;

            case ACTION_PREV:
                if (sListener != null) sListener.onPrevRequested();
                else openActivityWithAction(ACTION_PREV);
                break;

            case ACTION_SEEK:
                long seekPos = intent.getLongExtra(EXTRA_SEEK_POS_MS, -1);
                if (seekPos >= 0 && sListener != null) sListener.onSeekRequested(seekPos);
                break;

            case ACTION_STOP:
                if (sListener != null) sListener.onPauseRequested();
                stopPlaybackForeground();
                stopSelf();
                break;
        }
    }

    public void applyStateUpdate(boolean isPlaying, String title, String artist, String coverUrl,
                                 long posMs, long durMs) {
        mIsPlaying = isPlaying;
        if (title != null && !title.isEmpty()) mTitle = title;
        if (artist != null && !artist.isEmpty()) mArtist = artist;
        mPositionMs = posMs;
        mDurationMs = durMs;

        boolean coverChanged = (coverUrl != null && !coverUrl.equals(mCoverUrl)) || (coverUrl == null && mCoverUrl != null);
        mCoverUrl = coverUrl;

        updateMediaSessionState();

        if (coverChanged) {
            loadCoverBitmapAsync(coverUrl, () -> {
                updateMediaSessionMetadata();
                showOrUpdateNotification();
            });
        } else {
            updateMediaSessionMetadata();
            showOrUpdateNotification();
        }

        handleWakeLock(isPlaying);
    }

    private void updateMediaSessionState() {
        if (mMediaSession == null) return;
        int state = mIsPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;

        PlaybackState.Builder psb = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, mPositionMs, mIsPlaying ? 1.0f : 0.0f);

        mMediaSession.setPlaybackState(psb.build());
    }

    private void updateMediaSessionMetadata() {
        if (mMediaSession == null) return;
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, mArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Apex Music Player")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, mDurationMs);

        if (mCoverBitmap != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, mCoverBitmap);
            mb.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, mCoverBitmap);
        }

        mMediaSession.setMetadata(mb.build());
    }

    private void handleWakeLock(boolean isPlaying) {
        if (isPlaying) {
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                try {
                    mWakeLock.acquire(12 * 60 * 60 * 1000L); // 12 hours max
                } catch (Throwable ignored) {}
            }
        } else {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                try {
                    mWakeLock.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void showOrUpdateNotification() {
        try {
            Notification notification = buildNotification();

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "showOrUpdateNotification error", t);
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(
                this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevPI = buildActionPendingIntent(ACTION_PREV, 101);
        PendingIntent togglePI = buildActionPendingIntent(ACTION_TOGGLE_PLAY, 102);
        PendingIntent nextPI = buildActionPendingIntent(ACTION_NEXT, 103);
        PendingIntent stopPI = buildActionPendingIntent(ACTION_STOP, 104);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle();
        if (mMediaSession != null) {
            mediaStyle.setMediaSession(mMediaSession.getSessionToken());
        }
        mediaStyle.setShowActionsInCompactView(0, 1, 2);

        builder.setStyle(mediaStyle)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(mTitle)
                .setContentText(mArtist)
                .setSubText("Apex Music")
                .setContentIntent(contentPI)
                .setDeleteIntent(stopPI)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(mIsPlaying)
                .setOnlyAlertOnce(true);

        if (mCoverBitmap != null) {
            builder.setLargeIcon(mCoverBitmap);
        }

        // Action 0: Previous
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_skip_prev, "Previous", prevPI
        ).build());

        // Action 1: Play / Pause
        int playIcon = mIsPlaying ? R.drawable.ic_pause_arrow : R.drawable.ic_play_arrow;
        String playTitle = mIsPlaying ? "Pause" : "Play";
        builder.addAction(new Notification.Action.Builder(
                playIcon, playTitle, togglePI
        ).build());

        // Action 2: Next
        builder.addAction(new Notification.Action.Builder(
                R.drawable.ic_skip_next, "Next", nextPI
        ).build());

        return builder.build();
    }

    private PendingIntent buildActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MediaPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private void openActivityWithAction(String action) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "openActivityWithAction error", t);
        }
    }

    private void loadCoverBitmapAsync(String coverUrl, Runnable onDone) {
        if (coverUrl == null || coverUrl.isEmpty()) {
            mCoverBitmap = null;
            if (onDone != null) new Handler(Looper.getMainLooper()).post(onDone);
            return;
        }

        new Thread(() -> {
            Bitmap bmp = null;
            try {
                if (coverUrl.startsWith("data:image/") && coverUrl.contains(";base64,")) {
                    int comma = coverUrl.indexOf(',');
                    if (comma != -1) {
                        String b64 = coverUrl.substring(comma + 1);
                        byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
                        bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                    }
                } else {
                    String path = coverUrl;
                    if (path.contains("?")) path = path.substring(0, path.indexOf('?'));
                    if (path.startsWith("local-audio://")) path = path.substring("local-audio://".length());
                    if (path.startsWith("file://")) path = path.substring("file://".length());
                    path = Uri.decode(path);

                    File f = new File(path);
                    if (f.exists() && f.canRead()) {
                        bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                    } else if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                        URL u = new URL(coverUrl);
                        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        try (InputStream is = conn.getInputStream()) {
                            bmp = BitmapFactory.decodeStream(is);
                        }
                    }
                }
                if (bmp != null) {
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int s = Math.min(w, h);
                    if (w != h) {
                        bmp = Bitmap.createBitmap(bmp, (w - s) / 2, (h - s) / 2, s, s);
                    }
                    if (s > 300) {
                        bmp = Bitmap.createScaledBitmap(bmp, 300, 300, true);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "loadCoverBitmapAsync failed: " + t.getMessage());
            }

            final Bitmap finalBmp = bmp;
            mCoverBitmap = finalBmp;
            if (onDone != null) {
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }).start();
    }

    private void stopPlaybackForeground() {
        mIsPlaying = false;
        handleWakeLock(false);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            if (mNotificationManager != null) {
                mNotificationManager.cancel(NOTIFICATION_ID);
            }
            if (mMediaSession != null) {
                updateMediaSessionState();
            }
        } catch (Throwable t) {
            Log.e(TAG, "stopPlaybackForeground error", t);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        stopPlaybackForeground();
        if (mMediaSession != null) {
            try {
                mMediaSession.setActive(false);
                mMediaSession.release();
            } catch (Throwable ignored) {}
            mMediaSession = null;
        }
        if (mNoisyReceiver != null) {
            try {
                unregisterReceiver(mNoisyReceiver);
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "MediaPlaybackService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
