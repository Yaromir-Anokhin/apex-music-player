package com.apex.meshplayer;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class OkHttpDownloader extends Downloader {

    private final OkHttpClient client;

    public OkHttpDownloader(OkHttpClient client) {
        this.client = client;
    }

    public static OkHttpDownloader create() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        return new OkHttpDownloader(client);
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String httpMethod = request.httpMethod();
        String url = request.url();
        Map<String, List<String>> headers = request.headers();
        byte[] dataToSend = request.dataToSend();

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);

        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String headerName = entry.getKey();
                for (String headerValue : entry.getValue()) {
                    builder.addHeader(headerName, headerValue);
                }
            }
        }

        if ("POST".equalsIgnoreCase(httpMethod)) {
            RequestBody body = (dataToSend != null)
                    ? RequestBody.create(dataToSend, null)
                    : RequestBody.create(new byte[0], null);
            builder.post(body);
        } else if ("HEAD".equalsIgnoreCase(httpMethod)) {
            builder.head();
        } else {
            builder.get();
        }

        try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
            if (response.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }

            ResponseBody responseBody = response.body();
            String responseBodyString = (responseBody != null) ? responseBody.string() : "";
            String latestUrl = response.request().url().toString();
            Map<String, List<String>> responseHeaders = response.headers().toMultimap();

            return new Response(
                    response.code(),
                    response.message(),
                    responseHeaders,
                    responseBodyString,
                    latestUrl
            );
        }
    }
}
