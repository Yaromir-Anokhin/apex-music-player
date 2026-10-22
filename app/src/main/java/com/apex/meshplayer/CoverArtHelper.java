package com.apex.meshplayer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Cover art pipeline for YouTube downloads.
 *
 * 1. Downloads the YouTube thumbnail (maxresdefault -> hqdefault fallback).
 * 2. Crops it to a square album cover (removes 16:9 / letterbox black bars).
 * 3. Writes an ID3v2.3 tag (TIT2 / TPE1 / TALB / APIC) into the MP3, so the cover
 *    is visible in ANY player (Huawei Music, Mi Music, car head units, etc.).
 * 4. Publishes the final file to the public Downloads folder via MediaStore
 *    (Android 10+, works under scoped storage on Android 11-15) or via File API (Android 8-9).
 * 5. Stores a small sidecar JPEG in app-specific storage for fast display in the WebView UI.
 *
 * No extra dependencies: pure Java ID3 writer.
 */
public final class CoverArtHelper {

    private static final String TAG = "ApexCover";
    private static final int COVER_SIZE = 600;      // embedded cover (px)
    private static final int THUMB_SIZE = 300;      // UI sidecar cover (px)

    private static final OkHttpClient sHttp = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private CoverArtHelper() {}

    /** Result of processing a finished download. */
    public static final class Result {
        public File audioFile;
        public File coverFile; // may be null
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Full pipeline: tag temp file with cover, publish to Downloads, save UI cover.
     *
     * @param tmpFile     downloaded audio in app-specific storage (will be deleted)
     * @param displayName final file name, e.g. "Artist - Title.mp3"
     */
    public static Result processDownloadedTrack(Context ctx, File tmpFile, String displayName,
                                                String title, String artist, String videoId,
                                                String thumbnailUrl) throws IOException {
        boolean isMp3 = displayName.toLowerCase().endsWith(".mp3") || looksLikeMp3(tmpFile);
        byte[] coverJpeg = null;
        try {
            coverJpeg = fetchSquareCover(videoId, thumbnailUrl, COVER_SIZE);
        } catch (Throwable t) {
            Log.w(TAG, "Cover download failed: " + t.getMessage());
        }

        File toPublish = tmpFile;
        if (isMp3) {
            File tagged = new File(tmpFile.getParentFile(), tmpFile.getName() + ".tagged");
            try {
                writeId3Tag(tmpFile, tagged, title, artist, "YouTube Music", coverJpeg);
                toPublish = tagged;
            } catch (Throwable t) {
                Log.w(TAG, "ID3 tagging failed, publishing untagged: " + t.getMessage());
                tagged.delete();
            }
        }

        String mime = displayName.toLowerCase().endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        File published = publishToDownloads(ctx, toPublish, displayName, mime);

        // Clean up temp files
        if (toPublish != tmpFile) toPublish.delete();
        tmpFile.delete();

        Result r = new Result();
        r.audioFile = published;
        if (coverJpeg != null) {
            try {
                File cf = coverFileFor(ctx, published.getName());
                Bitmap bmp = BitmapFactory.decodeByteArray(coverJpeg, 0, coverJpeg.length);
                if (bmp != null) {
                    saveJpeg(scaleSquare(bmp, THUMB_SIZE), cf, 85);
                    r.coverFile = cf;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Sidecar cover save failed: " + t.getMessage());
            }
        }
        return r;
    }

    /**
     * Returns a UI cover for any local audio file. Uses sidecar cache, otherwise extracts
     * the embedded picture (APIC / covr / FLAC picture) once and caches it.
     */
    public static File findOrExtractCover(Context ctx, File audio) {
        try {
            File cf = coverFileFor(ctx, audio.getName());
            if (cf.exists() && cf.length() > 0) return cf;
            File none = new File(cf.getParentFile(), cf.getName() + ".none");
            if (none.exists() && none.lastModified() >= audio.lastModified()) return null;

            byte[] pic = null;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(audio.getAbsolutePath());
                pic = mmr.getEmbeddedPicture();
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
            if (pic != null) {
                Bitmap bmp = decodeSampled(pic, THUMB_SIZE * 2);
                if (bmp != null) {
                    saveJpeg(scaleSquare(cropSquare(bmp), THUMB_SIZE), cf, 85);
                    return cf;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            none.createNewFile();
        } catch (Throwable t) {
            Log.w(TAG, "findOrExtractCover failed for " + audio.getName() + ": " + t.getMessage());
        }
        return null;
    }

    /** Sidecar cover location (ASCII-safe name, so it can be used in a URL without escaping). */
    public static File coverFileFor(Context ctx, String audioFileName) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "covers");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        String key = audioFileName.replaceFirst("\\.[^.]+$", "");
        String hash = Integer.toHexString(key.hashCode()) + "_" + Integer.toHexString(key.length());
        return new File(dir, "c_" + hash + ".jpg");
    }

    /** "Artist - Topic" / "ArtistVEVO" -> "Artist". */
    public static String cleanArtist(String artist) {
        if (artist == null) return "YouTube";
        String a = artist.trim();
        a = a.replaceAll("\\s*-\\s*Topic$", "");
        a = a.replaceAll("(?i)VEVO$", "").trim();
        return a.isEmpty() ? artist.trim() : a;
    }

    // =====================================================================
    // Thumbnail -> square cover
    // =====================================================================

    public static byte[] fetchSquareCover(String videoId, String thumbnailUrl, int size) throws IOException {
        String[] candidates;
        if (videoId != null && !videoId.isEmpty()) {
            candidates = new String[]{
                    "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg",
                    "https://i.ytimg.com/vi/" + videoId + "/sddefault.jpg",
                    "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
                    thumbnailUrl
            };
        } else {
            candidates = new String[]{thumbnailUrl};
        }

        for (String url : candidates) {
            if (url == null || url.isEmpty()) continue;
            byte[] raw = httpGetBytes(url);
            if (raw == null || raw.length < 2000) continue; // YouTube returns tiny placeholder for missing sizes
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp == null) continue;
            if (bmp.getWidth() <= 120) continue; // 120x90 "no thumbnail" placeholder

            Bitmap square = scaleSquare(cropSquare(removeLetterbox(bmp)), size);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            square.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            return bos.toByteArray();
        }
        return null;
    }

    private static byte[] httpGetBytes(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) ApexMusic/1.1")
                    .build();
            try (Response res = sHttp.newCall(req).execute()) {
                if (res.isSuccessful() && res.body() != null) return res.body().bytes();
            }
        } catch (Throwable t) {
            Log.w(TAG, "GET " + url + " failed: " + t.getMessage());
        }
        return null;
    }

    /** hqdefault/sddefault are 4:3 with black bars around a 16:9 picture: cut them off. */
    private static Bitmap removeLetterbox(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (Math.abs((float) w / h - 4f / 3f) > 0.05f) return src;
        int contentH = Math.round(w * 9f / 16f);
        int top = (h - contentH) / 2;
        // Verify the bars are actually dark before cropping
        if (top > 2 && isDarkRow(src, top / 2) && isDarkRow(src, h - 1 - top / 2)) {
            return Bitmap.createBitmap(src, 0, top, w, contentH);
        }
        return src;
    }

    private static boolean isDarkRow(Bitmap b, int y) {
        int w = b.getWidth();
        long sum = 0;
        int samples = 0;
        for (int x = 0; x < w; x += Math.max(1, w / 32)) {
            int c = b.getPixel(x, y);
            sum += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
            samples++;
        }
        return samples > 0 && (sum / (samples * 3)) < 24;
    }

    private static Bitmap cropSquare(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == h) return src;
        int s = Math.min(w, h);
        return Bitmap.createBitmap(src, (w - s) / 2, (h - s) / 2, s, s);
    }

    private static Bitmap scaleSquare(Bitmap src, int size) {
        if (src.getWidth() <= size) return src;
        return Bitmap.createScaledBitmap(src, size, size, true);
    }

    private static Bitmap decodeSampled(byte[] data, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxDim && o.outHeight / (sample * 2) >= maxDim) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private static void saveJpeg(Bitmap bmp, File out, int quality) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
        }
    }

    // =====================================================================
    // ID3v2.3 writer
    // =====================================================================

    private static boolean looksLikeMp3(File f) {
        try (InputStream is = new FileInputStream(f)) {
            byte[] h = new byte[3];
            if (is.read(h) != 3) return false;
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
            return (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0; // MPEG frame sync
        } catch (IOException e) {
            return false;
        }
    }

    /** Copies src -> dst, replacing any existing ID3v2 tag with a fresh one. */
    public static void writeId3Tag(File src, File dst, String title, String artist,
                                   String album, byte[] jpegCover) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (title != null && !title.isEmpty()) writeTextFrame(frames, "TIT2", title);
        if (artist != null && !artist.isEmpty()) writeTextFrame(frames, "TPE1", artist);
        if (album != null && !album.isEmpty()) writeTextFrame(frames, "TALB", album);
        if (jpegCover != null && jpegCover.length > 0) writeApicFrame(frames, jpegCover);

        byte[] frameBytes = frames.toByteArray();
        int padding = 1024;
        int tagSize = frameBytes.length + padding;

        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {

            // Skip existing ID3v2 tag(s)
            in.mark(10);
            byte[] hdr = new byte[10];
            int n = readFully(in, hdr);
            if (n == 10 && hdr[0] == 'I' && hdr[1] == 'D' && hdr[2] == '3') {
                long oldSize = ((hdr[6] & 0x7F) << 21) | ((hdr[7] & 0x7F) << 14)
                        | ((hdr[8] & 0x7F) << 7) | (hdr[9] & 0x7F);
                if ((hdr[5] & 0x10) != 0) oldSize += 10; // footer present
                skipFully(in, oldSize);
            } else {
                in.reset();
            }

            // New header
            out.write(new byte[]{'I', 'D', '3', 3, 0, 0});
            out.write(synchsafe(tagSize));
            out.write(frameBytes);
            out.write(new byte[padding]);

            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
    }

    private static void writeTextFrame(ByteArrayOutputStream out, String id, String text) throws IOException {
        // Encoding 0x01 = UTF-16 with BOM (needed for Cyrillic in ID3v2.3)
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[1 + 2 + utf16.length];
        data[0] = 0x01;
        data[1] = (byte) 0xFF;
        data[2] = (byte) 0xFE;
        System.arraycopy(utf16, 0, data, 3, utf16.length);
        writeFrame(out, id, data);
    }

    private static void writeApicFrame(ByteArrayOutputStream out, byte[] jpeg) throws IOException {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        d.write(0x00);                                           // text encoding ISO-8859-1
        d.write("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        d.write(0x00);                                           // MIME terminator
        d.write(0x03);                                           // picture type: front cover
        d.write(0x00);                                           // empty description
        d.write(jpeg);
        writeFrame(out, "APIC", d.toByteArray());
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] data) throws IOException {
        out.write(id.getBytes(StandardCharsets.ISO_8859_1));
        int len = data.length; // ID3v2.3: plain 32-bit big endian size
        out.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
        out.write(new byte[]{0, 0}); // flags
        out.write(data);
    }

    private static byte[] synchsafe(int v) {
        return new byte[]{
                (byte) ((v >> 21) & 0x7F), (byte) ((v >> 14) & 0x7F),
                (byte) ((v >> 7) & 0x7F), (byte) (v & 0x7F)
        };
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r == -1) break;
            off += r;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() == -1) return;
                s = 1;
            }
            n -= s;
        }
    }

    // =====================================================================
    // Publishing to public Downloads (Android 8 ... 15)
    // =====================================================================

    @SuppressWarnings("deprecation")
    public static File publishToDownloads(Context ctx, File src, String displayName, String mime) throws IOException {
        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        // Replace a previous download of the same track instead of creating "Name (1).mp3"
        File existing = new File(publicDir, displayName);
        if (existing.exists()) {
            deleteFromMediaStore(ctx, existing);
            //noinspection ResultOfMethodCallIgnored
            existing.delete();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try {
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) throw new IOException("Cannot open MediaStore output");
                    copy(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }

            String actualName = displayName;
            try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) actualName = c.getString(0);
            }
            return new File(publicDir, actualName);
        }

        // Android 8-9: direct file access
        if (!publicDir.exists()) //noinspection ResultOfMethodCallIgnored
            publicDir.mkdirs();
        File dst = new File(publicDir, displayName);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        return dst;
    }

    @SuppressWarnings("deprecation")
    private static void deleteFromMediaStore(Context ctx, File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            ctx.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{file.getName(), Environment.DIRECTORY_DOWNLOADS + "%"});
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore delete failed (not owned?): " + t.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }
}
