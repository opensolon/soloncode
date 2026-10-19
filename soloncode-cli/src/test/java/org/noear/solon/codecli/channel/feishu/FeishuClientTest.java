package org.noear.solon.codecli.channel.feishu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 本地 HTTP mock，不使用飞书账号、不请求外网。 */
class FeishuClientTest {
    private HttpServer server;
    private String endpoint;
    private String appId;
    private final List<Request> requests = new ArrayList<>();
    private int tokenStatus;
    private String tokenResponse;
    private int apiStatus;
    private String apiResponse;

    @BeforeEach
    void startServer() throws IOException {
        appId = "test-" + UUID.randomUUID();
        tokenStatus = apiStatus = 200;
        tokenResponse = "{\"code\":0,\"tenant_access_token\":\"mock-token\",\"expire\":7200}";
        apiResponse = "{\"code\":0,\"data\":{\"image_key\":\"img_mock\",\"message_id\":\"om_mock\"}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/open-apis/", exchange -> {
            Request request = new Request(exchange);
            synchronized (requests) {
                requests.add(request);
            }
            boolean token = request.path.contains("/auth/");
            byte[] response = (token ? tokenResponse : apiResponse).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(token ? tokenStatus : apiStatus, response.length);
            try {
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/open-apis";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void uploadsPngAndJpegWithLibraryMultipart() throws IOException {
        for (String format : Arrays.asList("png", "jpeg")) {
            byte[] image = image(format);
            String filename = "local." + format;
            assertEquals("img_mock", upload(image, filename));
            Request request = lastRequest();
            assertEquals("POST", request.method);
            assertEquals("/open-apis/im/v1/images", request.path);
            assertEquals("Bearer mock-token", request.authorization);
            assertTrue(request.contentType.startsWith("multipart/form-data;"));
            assertTrue(request.contentType.contains("boundary="));
            String body = new String(request.body, StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"image_type\""), body);
            assertTrue(body.contains("\r\n\r\nmessage\r\n"), body);
            assertTrue(body.contains("name=\"image\""), body);
            assertTrue(body.contains("filename=\"" + filename + "\""), body);
            assertTrue(body.contains("image/" + format), body);
            assertTrue(body.contains(new String(image, StandardCharsets.ISO_8859_1)), "图片字节须原样传输");
        }
        assertEquals(1, tokenRequests());
        ONode credentials = ONode.ofJson(new String(requests.get(0).body, StandardCharsets.UTF_8));
        assertEquals(appId, credentials.get("app_id").getString());
        assertEquals("secret", credentials.get("app_secret").getString());
    }

    @Test
    void sendsImageWithJsonStringContentStableUuidAndCachedToken() throws IOException {
        upload(image("png"), "local.png");
        String key = "img_\"escaped\\key";
        for (int i = 0; i < 2; i++) {
            assertEquals("om_mock", send(key, "stable-retry-id"));
            Request request = lastRequest();
            assertEquals("POST", request.method);
            assertEquals("/open-apis/im/v1/messages", request.path);
            assertEquals("receive_id_type=open_id", request.query);
            assertEquals("Bearer mock-token", request.authorization);
            assertTrue(request.contentType.startsWith("application/json"));
            ONode body = ONode.ofJson(new String(request.body, StandardCharsets.UTF_8));
            assertEquals("ou_mock", body.get("receive_id").getString());
            assertEquals("image", body.get("msg_type").getString());
            assertEquals("stable-retry-id", body.get("uuid").getString());
            assertTrue(body.get("content").isString(), "content 必须是 JSON 字符串");
            assertEquals(key, ONode.ofJson(body.get("content").getString()).get("image_key").getString());
        }
        assertEquals(1, tokenRequests());
    }

    @Test
    void isolatesTokenByAppId() {
        send("img_mock", "one");
        assertEquals("om_mock", FeishuClient.sendImageMessage(appId + "-other", "secret", "ou_mock",
                "img_mock", "two", endpoint));
        assertEquals(2, tokenRequests());
    }

    @Test
    void refreshesTokenNearExpiry() {
        tokenResponse = "{\"code\":0,\"tenant_access_token\":\"short-token\",\"expire\":60}";
        send("img_mock", "one");
        send("img_mock", "two");
        assertEquals(2, tokenRequests());
        assertEquals("Bearer short-token", lastRequest().authorization);
    }

    @Test
    void reportsUploadHttpAndBusinessErrors() throws IOException {
        byte[] png = image("png");
        apiStatus = 413;
        IllegalStateException http = assertThrows(IllegalStateException.class, () -> upload(png, "a.png"));
        assertTrue(http.getMessage().contains("uploadImage HTTP error: status=413"));
        apiStatus = 200;
        apiResponse = "{\"code\":234001,\"msg\":\"invalid image\"}";
        IllegalStateException business = assertThrows(IllegalStateException.class, () -> upload(png, "a.png"));
        assertTrue(business.getMessage().contains("business error: code=234001"));
        assertTrue(business.getMessage().contains("invalid image"));
    }

    @Test
    void reportsSendHttpAndBusinessErrors() {
        apiStatus = 503;
        assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                .getMessage().contains("sendImageMessage HTTP error: status=503"));
        apiStatus = 200;
        apiResponse = "{\"code\":230001,\"msg\":\"invalid recipient\"}";
        String message = assertThrows(IllegalStateException.class, () -> send("img_mock", "one")).getMessage();
        assertTrue(message.contains("code=230001"));
        assertTrue(message.contains("invalid recipient"));
    }

    @Test
    void propagatesTokenErrorsWithoutSendingImage() {
        tokenStatus = 401;
        assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                .getMessage().contains("getTenantAccessToken HTTP error: status=401"));
        tokenStatus = 200;
        tokenResponse = "{\"code\":10003,\"msg\":\"bad credentials\"}";
        assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                .getMessage().contains("code=10003"));
        assertEquals(2, requests.size());
        assertEquals(2, tokenRequests());
    }

    @Test
    void rejectsMissingTokenAndDoesNotCacheIt() {
        tokenResponse = "{\"code\":0,\"expire\":7200}";
        assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                .getMessage().contains("tenant_access_token"));
        tokenResponse = "{\"code\":0,\"tenant_access_token\":\"mock-token\",\"expire\":7200}";
        assertEquals("om_mock", send("img_mock", "one"));
        assertEquals(2, tokenRequests());
    }

    @Test
    void rejectsMalformedAndIncompleteApiResponses() throws IOException {
        for (String response : Arrays.asList("not json", "{}", "{\"data\":{\"image_key\":\"x\"}}")) {
            apiResponse = response;
            assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                    .getMessage().contains("invalid JSON response"));
        }
        apiResponse = "{\"code\":0,\"data\":{}}";
        assertTrue(assertThrows(IllegalStateException.class, () -> send("img_mock", "one"))
                .getMessage().contains("message_id"));
        byte[] png = image("png");
        assertTrue(assertThrows(IllegalStateException.class, () -> upload(png, "a.png"))
                .getMessage().contains("image_key"));
    }

    @Test
    void rejectsInvalidParametersBeforeAnyHttpRequest() throws IOException {
        byte[] png = image("png");
        assertThrows(IllegalArgumentException.class, () -> upload(null, "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload(new byte[0], "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload("GIF89a".getBytes(StandardCharsets.UTF_8), "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload(png, ""));
        assertThrows(IllegalArgumentException.class, () -> upload(png, "bad\r\nname.png"));
        assertThrows(IllegalArgumentException.class, () -> upload(png, "dir/a.png"));
        assertThrows(IllegalArgumentException.class, () -> send("", "one"));
        assertThrows(IllegalArgumentException.class, () -> send("img_mock", " "));
        assertThrows(IllegalArgumentException.class, () -> send("img_mock", new String(new char[51]).replace('\0', 'x')));
        assertThrows(IllegalArgumentException.class, () -> FeishuClient.sendImageMessage(appId, "secret", " ", "img_mock", "one", endpoint));
        assertThrows(IllegalArgumentException.class, () -> FeishuClient.sendImageMessage(appId, null, "ou_mock", "img_mock", "one", endpoint));
        assertEquals(0, requests.size());
    }

    @Test
    void reportsTransportFailureWithCause() {
        server.stop(0);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> send("img_mock", "one"));
        assertTrue(error.getMessage().contains("getTenantAccessToken HTTP transport/response error"));
        assertNotNull(error.getCause());
    }

    private String upload(byte[] image, String filename) {
        return FeishuClient.uploadImage(appId, "secret", image, filename, endpoint);
    }

    private String send(String imageKey, String uuid) {
        return FeishuClient.sendImageMessage(appId, "secret", "ou_mock", imageKey, uuid, endpoint);
    }

    private Request lastRequest() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    private long tokenRequests() {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path.contains("/auth/")).count();
        }
    }

    private static byte[] image(String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out));
        return out.toByteArray();
    }

    private static class Request {
        final String method;
        final String path;
        final String query;
        final String authorization;
        final String contentType;
        final byte[] body;

        Request(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getQuery();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                }
                body = output.toByteArray();
            }
        }
    }
}
