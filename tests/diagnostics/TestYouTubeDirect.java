import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class TestYouTubeDirect {

    private static final OkHttpClient sHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

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

    public static JSONObject resolveVideo(String videoId) {
        JSONObject res = new JSONObject();
        try {
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
            }

            // Duration from watch page
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

            res.put("success", true);
            res.put("id", videoId);
            res.put("title", title);
            res.put("artist", author);
            res.put("durationSec", durSec);
            res.put("durationStr", String.format("%d:%02d", m, s));
            res.put("duration", String.format("%d:%02d", m, s));
            res.put("thumbnail", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
            res.put("url", "https://www.youtube.com/watch?v=" + videoId);
            res.put("isYt", true);
            return res;
        } catch (Throwable t) {
            res.put("success", false);
            res.put("error", t.getMessage());
            return res;
        }
    }

    public static JSONObject searchVideos(String query) {
        JSONObject res = new JSONObject();
        try {
            String searchUrl = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build();

            try (Response response = sHttpClient.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    res.put("success", false);
                    res.put("error", "HTTP " + response.code());
                    return res;
                }
                String html = response.body().string();
                Matcher m = Pattern.compile("var ytInitialData\\s*=\\s*(\\{.*?\\});</script>").matcher(html);
                if (!m.find()) {
                    m = Pattern.compile("ytInitialData\\s*=\\s*(\\{.*?\\});").matcher(html);
                }
                if (!m.find()) {
                    res.put("success", false);
                    res.put("error", "ytInitialData not found");
                    return res;
                }

                JSONObject data = new JSONObject(m.group(1));
                JSONArray contents = data.optJSONObject("contents")
                        .optJSONObject("twoColumnSearchResultsRenderer")
                        .optJSONObject("primaryContents")
                        .optJSONObject("sectionListRenderer")
                        .optJSONArray("contents");

                JSONArray items = new JSONArray();
                if (contents != null) {
                    for (int i = 0; i < contents.length(); i++) {
                        JSONObject section = contents.optJSONObject(i);
                        if (section == null) continue;
                        JSONArray itemSection = section.optJSONObject("itemSectionRenderer") != null
                                ? section.optJSONObject("itemSectionRenderer").optJSONArray("contents")
                                : null;
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
                            jo.put("isYt", true);
                            items.put(jo);
                        }
                    }
                }

                res.put("success", true);
                res.put("items", items);
                if (items.length() > 0) {
                    res.put("topTrack", items.getJSONObject(0));
                }
                return res;
            }
        } catch (Throwable t) {
            res.put("success", false);
            res.put("error", t.getMessage());
            return res;
        }
    }

    public static void main(String[] args) {
        System.out.println("--- TEST 1: YouTube Music Link Resolution ---");
        String ytMusicUrl = "https://music.youtube.com/watch?v=kXYiU_JCYtU&feature=share";
        String id = extractVideoId(ytMusicUrl);
        System.out.println("Extracted ID: " + id);
        JSONObject resolved = resolveVideo(id);
        System.out.println("Resolved: " + resolved.toString(2));

        System.out.println("\n--- TEST 2: YouTube Search ---");
        JSONObject searchRes = searchVideos("Linkin Park Numb");
        System.out.println("Search Success: " + searchRes.optBoolean("success"));
        JSONArray items = searchRes.optJSONArray("items");
        System.out.println("Found items: " + (items != null ? items.length() : 0));
        if (items != null && items.length() > 0) {
            for (int i = 0; i < Math.min(3, items.length()); i++) {
                JSONObject it = items.getJSONObject(i);
                System.out.println("  " + (i + 1) + ". " + it.optString("title") + " (" + it.optString("durationStr") + ") by " + it.optString("artist"));
            }
        }
    }
}
