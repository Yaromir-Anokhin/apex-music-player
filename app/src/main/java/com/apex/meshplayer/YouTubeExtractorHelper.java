package com.apex.meshplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class YouTubeExtractorHelper {

    private static final String TAG = "ApexYouTube";
    private static volatile boolean sInitialized = false;
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();

    private static final OkHttpClient sHttpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public interface Callback {
        void onResult(String jsonResult);
    }

    private static void log(String level, String msg, Throwable t) {
        try {
            if ("e".equals(level)) android.util.Log.e(TAG, msg, t);
            else if ("w".equals(level)) android.util.Log.w(TAG, msg, t);
            else android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
            System.out.println("[" + TAG + " " + level + "] " + msg + (t != null ? " : " + t.getMessage() : ""));
            if (t != null) t.printStackTrace(System.out);
        }
    }

    public static synchronized void init() {
        if (!sInitialized) {
            sInitialized = true;
            log("i", "YouTubeExtractorHelper initialized successfully", null);
        }
    }

    /**
     * Extracts an 11-character YouTube video ID from any YouTube or YouTube Music URL.
     */
    public static String extractVideoId(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.matches("^[a-zA-Z0-9_-]{11}$")) return url;

        try {
            Matcher mv = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})").matcher(url);
            if (mv.find()) return mv.group(1);
        } catch (Throwable ignored) {}

        try {
            Matcher ms = Pattern.compile("(?:youtu\\.be/|embed/|shorts/|v/|live/|track/)([a-zA-Z0-9_-]{11})").matcher(url);
            if (ms.find()) return ms.group(1);
        } catch (Throwable ignored) {}

        return null;
    }

    public static String normalizeUrl(String input) {
        if (input == null) return "";
        input = input.trim();
        String videoId = extractVideoId(input);
        if (videoId != null && !videoId.isEmpty()) {
            return "https://www.youtube.com/watch?v=" + videoId;
        }
        return input;
    }

    /**
     * Handles search query or direct YouTube/YouTube Music URL.
     */
    public static void searchOrResolveAsync(final String query, final Callback callback) {
        sExecutor.execute(() -> {
            try {
                init();
                String trimmed = (query != null) ? query.trim() : "";
                if (trimmed.isEmpty()) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "Empty search query");
                    callback.onResult(err.toString());
                    return;
                }

                String videoId = extractVideoId(trimmed);
                boolean isUrl = videoId != null || trimmed.contains("youtube.com/") || trimmed.contains("youtu.be/");

                if (isUrl && videoId != null) {
                    JSONObject singleResult = resolveVideoDirect(videoId);
                    callback.onResult(singleResult.toString());
                    return;
                }

                // Keyword search using YouTube's direct search results parser
                JSONObject searchResult = searchVideosDirect(trimmed);
                callback.onResult(searchResult.toString());

            } catch (Throwable t) {
                log("e", "Critical search guard caught: " + t.getMessage(), t);
                try {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "Search error: " + (t.getMessage() != null ? t.getMessage() : "Unknown error"));
                    callback.onResult(err.toString());
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * Resolves a video ID into playback and metadata information.
     */
    public static JSONObject resolveVideoDirect(String videoId) {
        JSONObject res = new JSONObject();
        try {
            // 1. YouTube official oEmbed API (Fast & Reliable, never blocked)
            String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";
            Request req = new Request.Builder()
                    .url(oembedUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Huawei P20 Pro) ApexPlayer/1.0")
                    .build();

            String title = "YouTube Track";
            String author = "YouTube Music";

            try (Response response = sHttpClient.newCall(req).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject oembed = new JSONObject(response.body().string());
                    title = oembed.optString("title", title);
                    author = oembed.optString("author_name", author);
                }
            } catch (Throwable t) {
                log("w", "oEmbed failed: " + t.getMessage(), t);
            }

            // 2. Fetch duration from watch page
            long durSec = 210;
            try {
                Request watchReq = new Request.Builder()
                        .url("https://www.youtube.com/watch?v=" + videoId)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build();
                try (Response wRes = sHttpClient.newCall(watchReq).execute()) {
                    if (wRes.isSuccessful() && wRes.body() != null) {
                        String html = wRes.body().string();
                        Matcher m = Pattern.compile("\"approxDurationMs\"\\s*:\\s*\"(\\d+)\"").matcher(html);
                        if (m.find()) {
                            durSec = Long.parseLong(m.group(1)) / 1000;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            long m = durSec / 60;
            long s = durSec % 60;
            String durStr = String.format("%d:%02d", m, s);

            res.put("success", true);
            res.put("id", videoId);
            res.put("title", title);
            res.put("artist", author);
            res.put("durationSec", durSec);
            res.put("durationStr", durStr);
            res.put("duration", durStr);
            res.put("thumbnail", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
            res.put("url", "https://www.youtube.com/watch?v=" + videoId);
            res.put("audioUrl", "https://www.youtube.com/watch?v=" + videoId); // Official embed playback
            res.put("isYt", true);
            return res;

        } catch (Throwable t) {
            log("e", "resolveVideoDirect failed: " + t.getMessage(), t);
            try {
                res.put("success", false);
                res.put("error", "Failed to load video details: " + t.getMessage());
            } catch (Throwable ignored) {}
            return res;
        }
    }

    /**
     * Direct YouTube HTML search parser (extracts ytInitialData).
     */
    public static JSONObject searchVideosDirect(String query) {
        JSONObject res = new JSONObject();
        try {
            String searchUrl = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build();

            try (Response response = sHttpClient.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    res.put("success", false);
                    res.put("error", "YouTube search error: HTTP " + response.code());
                    return res;
                }

                String html = response.body().string();
                int idx = html.indexOf("ytInitialData = {");
                if (idx == -1) idx = html.indexOf("ytInitialData={");
                if (idx == -1) idx = html.indexOf("var ytInitialData = {");

                if (idx == -1) {
                    res.put("success", false);
                    res.put("error", "Unable to locate YouTube search results");
                    return res;
                }

                int startBrace = html.indexOf('{', idx);
                int endSemicolon = html.indexOf(";</script>", startBrace);
                if (endSemicolon == -1) {
                    endSemicolon = html.indexOf("};", startBrace);
                    if (endSemicolon != -1) endSemicolon += 1;
                }

                if (startBrace == -1 || endSemicolon == -1 || endSemicolon <= startBrace) {
                    res.put("success", false);
                    res.put("error", "Unable to parse YouTube search results");
                    return res;
                }

                String jsonStr = html.substring(startBrace, endSemicolon);
                JSONObject data = new JSONObject(jsonStr);
                JSONArray contents = null;
                try {
                    contents = data.getJSONObject("contents")
                            .getJSONObject("twoColumnSearchResultsRenderer")
                            .getJSONObject("primaryContents")
                            .getJSONObject("sectionListRenderer")
                            .getJSONArray("contents");
                } catch (Throwable ignored) {}

                JSONArray items = new JSONArray();
                if (contents != null) {
                    for (int i = 0; i < contents.length(); i++) {
                        JSONObject section = contents.optJSONObject(i);
                        if (section == null) continue;
                        JSONObject isr = section.optJSONObject("itemSectionRenderer");
                        if (isr == null) continue;
                        JSONArray itemSection = isr.optJSONArray("contents");
                        if (itemSection == null) continue;

                        for (int j = 0; j < itemSection.length(); j++) {
                            JSONObject item = itemSection.optJSONObject(j);
                            if (item == null || !item.has("videoRenderer")) continue;
                            JSONObject vr = item.getJSONObject("videoRenderer");
                            String vid = vr.optString("videoId", "");
                            if (vid.isEmpty()) continue;

                            String title = "Unknown";
                            try {
                                title = vr.getJSONObject("title").getJSONArray("runs").getJSONObject(0).optString("text", title);
                            } catch (Throwable ignored) {}

                            String author = "YouTube";
                            try {
                                author = vr.getJSONObject("ownerText").getJSONArray("runs").getJSONObject(0).optString("text", author);
                            } catch (Throwable ignored) {}

                            String dur = "3:30";
                            try {
                                dur = vr.getJSONObject("lengthText").optString("simpleText", dur);
                            } catch (Throwable ignored) {}

                            String thumb = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
                            try {
                                JSONArray thumbs = vr.getJSONObject("thumbnail").getJSONArray("thumbnails");
                                if (thumbs.length() > 0) {
                                    thumb = thumbs.getJSONObject(thumbs.length() - 1).optString("url", thumb);
                                }
                            } catch (Throwable ignored) {}

                            JSONObject jo = new JSONObject();
                            jo.put("id", vid);
                            jo.put("title", title);
                            jo.put("artist", author);
                            jo.put("durationStr", dur);
                            jo.put("duration", dur);
                            jo.put("thumbnail", thumb);
                            jo.put("url", "https://www.youtube.com/watch?v=" + vid);
                            jo.put("audioUrl", "https://www.youtube.com/watch?v=" + vid);
                            jo.put("isYt", true);
                            items.put(jo);
                        }
                    }
                }

                if (items.length() == 0) {
                    res.put("success", false);
                    res.put("error", "No tracks found for your search");
                    return res;
                }

                res.put("success", true);
                res.put("items", items);
                res.put("topTrack", items.getJSONObject(0));
                return res;
            }
        } catch (Throwable t) {
            log("e", "searchVideosDirect error: " + t.getMessage(), t);
            try {
                res.put("success", false);
                res.put("error", "Search error: " + t.getMessage());
            } catch (Throwable ignored) {}
            return res;
        }
    }

    /**
     * Converts YouTube track to direct MP3 download URL using loader.to API.
     */
    public static void requestMp3DownloadAsync(final String videoId, final Callback callback) {
        sExecutor.execute(() -> {
            // Tier 1: Try Local LAN Server (100% reliable 320 kbps MP3 conversion via yt-dlp on PC)
            String[] lanHosts = new String[]{
                    "http://192.168.1.107:8080/api/youtube?q=" + videoId,
                    "http://10.0.2.2:8080/api/youtube?q=" + videoId
            };
            for (String lanUrl : lanHosts) {
                try {
                    Request lanReq = new Request.Builder()
                            .url(lanUrl)
                            .header("User-Agent", "ApexMusic/1.0 (Android 10)")
                            .build();

                    OkHttpClient fastClient = sHttpClient.newBuilder()
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(25, TimeUnit.SECONDS)
                            .build();

                    try (Response lanRes = fastClient.newCall(lanReq).execute()) {
                        if (lanRes.isSuccessful() && lanRes.body() != null) {
                            JSONObject data = new JSONObject(lanRes.body().string());
                            String dl = data.optString("downloadUrl", data.optString("audioUrl", ""));
                            if (!dl.isEmpty()) {
                                log("i", "Retrieved MP3 from LAN server: " + dl, null);
                                JSONObject ok = new JSONObject();
                                ok.put("success", true);
                                ok.put("downloadUrl", dl);
                                ok.put("format", "MP3");
                                callback.onResult(ok.toString());
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("d", "LAN host check failed (" + lanUrl + "): " + t.getMessage(), null);
                }
            }

            // Tier 2: Public Cloud Converter (loader.to) with whitespace-tolerant decoding
            try {
                String ytUrl = "https://www.youtube.com/watch?v=" + videoId;
                String startUrl = "https://loader.to/ajax/download.php?format=mp3&url=" + URLEncoder.encode(ytUrl, "UTF-8");

                Request req = new Request.Builder()
                        .url(startUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build();

                String streamId = null;
                try (Response res = sHttpClient.newCall(req).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        JSONObject data = new JSONObject(res.body().string());
                        String b64Content = data.optString("content", "");
                        if (!b64Content.isEmpty()) {
                            byte[] decodedBytes = null;
                            try {
                                String cleanB64 = b64Content.replaceAll("\\s+", "");
                                decodedBytes = java.util.Base64.getDecoder().decode(cleanB64);
                            } catch (Throwable ignored) {
                                try {
                                    decodedBytes = android.util.Base64.decode(b64Content, android.util.Base64.DEFAULT);
                                } catch (Throwable ignored2) {}
                            }
                            if (decodedBytes != null) {
                                String html = new String(decodedBytes, "UTF-8");
                                Matcher m = Pattern.compile("id=[\"']card-(v2_stream_[a-f0-9]+)[\"']").matcher(html);
                                if (m.find()) {
                                    streamId = m.group(1);
                                } else {
                                    Matcher m2 = Pattern.compile("(v2_stream_[a-f0-9]+)").matcher(html);
                                    if (m2.find()) {
                                        streamId = m2.group(1);
                                    }
                                }
                            }
                        }
                    }
                }

                if (streamId != null) {
                    String downloadUrl = null;
                    for (int attempt = 0; attempt < 15; attempt++) {
                        Thread.sleep(2000);
                        String progUrl = "https://loader.to/ajax/progress.php?id=" + streamId;
                        Request pReq = new Request.Builder()
                                .url(progUrl)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build();

                        try (Response pRes = sHttpClient.newCall(pReq).execute()) {
                            if (pRes.isSuccessful() && pRes.body() != null) {
                                JSONObject pData = new JSONObject(pRes.body().string());
                                if (pData.optInt("success", 0) == 1 && !pData.optString("download_url", "").isEmpty()) {
                                    downloadUrl = pData.optString("download_url");
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (downloadUrl != null && !downloadUrl.isEmpty()) {
                        JSONObject ok = new JSONObject();
                        ok.put("success", true);
                        ok.put("downloadUrl", downloadUrl);
                        ok.put("format", "MP3");
                        callback.onResult(ok.toString());
                        return;
                    }
                }
            } catch (Throwable t) {
                log("w", "loader.to failed: " + t.getMessage(), t);
            }

            // Tier 3: Invidious Audio Stream Fallback
            String[] invidiousInstances = new String[]{
                    "https://invidious.f5.si",
                    "https://inv.nadeko.net",
                    "https://yewtu.be"
            };
            for (String inst : invidiousInstances) {
                try {
                    String invUrl = inst + "/api/v1/videos/" + videoId;
                    Request invReq = new Request.Builder()
                            .url(invUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .build();
                    OkHttpClient invClient = sHttpClient.newBuilder()
                            .connectTimeout(3, TimeUnit.SECONDS)
                            .readTimeout(6, TimeUnit.SECONDS)
                            .build();
                    try (Response invRes = invClient.newCall(invReq).execute()) {
                        if (invRes.isSuccessful() && invRes.body() != null) {
                            JSONObject invData = new JSONObject(invRes.body().string());
                            JSONArray formats = invData.optJSONArray("adaptiveFormats");
                            if (formats != null) {
                                for (int i = 0; i < formats.length(); i++) {
                                    JSONObject fmt = formats.getJSONObject(i);
                                    String type = fmt.optString("type", "");
                                    String url = fmt.optString("url", "");
                                    if (type.contains("audio") && !url.isEmpty()) {
                                        JSONObject ok = new JSONObject();
                                        ok.put("success", true);
                                        ok.put("downloadUrl", url);
                                        ok.put("format", "M4A");
                                        callback.onResult(ok.toString());
                                        return;
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // If all tiers fail
            try {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("error", "Audio conversion servers temporarily busy. Please try again.");
                callback.onResult(err.toString());
            } catch (Throwable ignored) {}
        });
    }

    public static void getAudioStreamAsync(final String videoUrl, final Callback callback) {
        sExecutor.execute(() -> {
            try {
                String videoId = extractVideoId(videoUrl);
                if (videoId != null) {
                    JSONObject res = resolveVideoDirect(videoId);
                    callback.onResult(res.toString());
                } else {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "Invalid video URL");
                    callback.onResult(err.toString());
                }
            } catch (Throwable t) {
                log("e", "getAudioStreamAsync error: " + t.getMessage(), t);
                try {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "Error: " + t.getMessage());
                    callback.onResult(err.toString());
                } catch (Throwable ignored) {}
            }
        });
    }
}
