package com.apex.meshplayer;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ExtractorTest {

    @Test
    public void testYouTubeMusicUrl() throws InterruptedException {
        String url = "https://music.youtube.com/watch?v=kXYiU_JCYtU";
        System.out.println("Testing YouTube Music URL: " + url);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();

        YouTubeExtractorHelper.searchOrResolveAsync(url, jsonResult -> {
            resultRef.set(jsonResult);
            latch.countDown();
        });

        boolean ok = latch.await(20, TimeUnit.SECONDS);
        assertTrue("Timeout resolving YouTube Music URL", ok);

        String result = resultRef.get();
        System.out.println("Result: " + result);
        assertNotNull("Result is null", result);

        try {
            JSONObject json = new JSONObject(result);
            boolean success = json.optBoolean("success", false);
            System.out.println("Success: " + success);
            if (!success) {
                System.out.println("Error msg: " + json.optString("error"));
            }
            assertTrue("Expected success=true for YouTube Music URL, got: " + result, success);
            String audioUrl = json.optString("audioUrl", "");
            System.out.println("Audio URL length: " + audioUrl.length());
            assertTrue("Expected non-empty audioUrl", !audioUrl.isEmpty());
        } catch (Exception e) {
            fail("JSON parse error: " + e.getMessage());
        }
    }

    @Test
    public void testKeywordSearch() throws InterruptedException {
        String query = "Lo-Fi Hip Hop Beats";
        System.out.println("Testing Keyword Search: " + query);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();

        YouTubeExtractorHelper.searchOrResolveAsync(query, jsonResult -> {
            resultRef.set(jsonResult);
            latch.countDown();
        });

        boolean ok = latch.await(25, TimeUnit.SECONDS);
        assertTrue("Timeout searching keyword", ok);

        String result = resultRef.get();
        System.out.println("Result: " + (result != null ? result.substring(0, Math.min(result.length(), 200)) : "null"));
        assertNotNull("Result is null", result);

        try {
            JSONObject json = new JSONObject(result);
            boolean success = json.optBoolean("success", false);
            assertTrue("Expected success=true, got: " + result, success);
            int count = json.optJSONArray("items") != null ? json.optJSONArray("items").length() : 0;
            System.out.println("Found items count: " + count);
            assertTrue("Expected at least 1 item", count > 0);
        } catch (Exception e) {
            fail("JSON parse error: " + e.getMessage());
        }
    }
}
