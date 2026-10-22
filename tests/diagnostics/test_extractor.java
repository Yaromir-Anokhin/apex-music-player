import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class test_extractor {

    public static class SimpleDownloader extends Downloader {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String httpMethod = request.httpMethod();
            String url = request.url();
            Map<String, List<String>> headers = request.headers();
            byte[] dataToSend = request.dataToSend();

            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);

            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    for (String headerValue : entry.getValue()) {
                        builder.addHeader(entry.getKey(), headerValue);
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

    public static void main(String[] args) {
        try {
            System.out.println("Initializing NewPipe with fetchIosClient=true...");
            YoutubeStreamExtractor.setFetchIosClient(true);
            NewPipe.init(new SimpleDownloader(), Localization.DEFAULT);

            String testUrl = "https://www.youtube.com/watch?v=kXYiU_JCYtU"; // Linkin Park Numb
            System.out.println("Getting info for: " + testUrl);
            StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, testUrl);
            System.out.println("Title: " + info.getName());
            System.out.println("Streams found: " + info.getAudioStreams().size());
            for (AudioStream as : info.getAudioStreams()) {
                System.out.println("Audio format: " + as.getFormat() + ", bitrate: " + as.getAverageBitrate() + ", url len: " + as.getUrl().length());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
