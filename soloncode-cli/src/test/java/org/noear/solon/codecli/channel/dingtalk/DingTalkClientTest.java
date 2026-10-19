package org.noear.solon.codecli.channel.dingtalk;

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

import static org.junit.jupiter.api.Assertions.*;

/** 本地 HTTP mock，不使用钉钉账号、不请求外网、不修改全局端点。 */
class DingTalkClientTest {
    private HttpServer server;
    private String endpoint;
    private final List<Request> requests = new ArrayList<>();
    private int status;
    private String response;

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        response = "{\"errcode\":0,\"media_id\":\"media_mock\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            synchronized (requests) {
                requests.add(new Request(exchange));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try {
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void uploadsPngAndJpegWithLibraryMultipartAndOriginalBytes() throws IOException {
        for (String format : Arrays.asList("png", "jpeg")) {
            byte[] data = image(format);
            String filename = "local." + format;
            assertEquals("media_mock", upload(data, filename));
            Request request = lastRequest();
            assertEquals("POST", request.method);
            assertEquals("/media/upload", request.path);
            assertEquals("access_token=mock-token", request.query);
            assertNull(request.token);
            assertTrue(request.contentType.startsWith("multipart/form-data;"));
            assertTrue(request.contentType.contains("boundary="));
            String body = new String(request.body, StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"type\""));
            assertTrue(body.contains("\r\n\r\nimage\r\n"));
            assertTrue(body.contains("name=\"media\""));
            assertTrue(body.contains("filename=\"image." + ("png".equals(format) ? "png" : "jpg") + "\""));
            assertTrue(body.contains("image/" + format));
            assertTrue(body.contains(new String(data, StandardCharsets.ISO_8859_1)), "图片字节须原样传输");
        }
    }

    @Test
    void usesSafeFormatBasedFilenameWithoutChangingImageBytes() throws IOException {
        for (String format : Arrays.asList("png", "jpeg")) {
            byte[] data = image(format);
            for (String filename : Arrays.asList("截图 首页.png", "截图\"首页.png",
                    "a\r\nX-Injected: yes.png", "mismatched.gif")) {
                assertEquals("media_mock", upload(data, filename));
                String body = new String(lastRequest().body, StandardCharsets.ISO_8859_1);
                String safeName = "png".equals(format) ? "image.png" : "image.jpg";
                assertTrue(body.contains("filename=\"" + safeName + "\"\r\n"));
                assertFalse(body.contains("X-Injected"));
                assertTrue(body.contains("Content-Type: image/" + format));
                assertTrue(body.contains(new String(data, StandardCharsets.ISO_8859_1)));
            }
        }
    }

    @Test
    void encodesTokenInUploadQuery() throws IOException {
        assertEquals("media_mock", DingTalkClient.uploadImage("a+&= ?/中文", image("png"), "a.png", endpoint));
        assertEquals("access_token=a%2B%26%3D+%3F%2F%E4%B8%AD%E6%96%87", lastRequest().query);
    }

    @Test
    void sendsOneRecipientAndJsonStringMarkdownWithInlineImage() {
        response = "";
        String text = "图片前\n![alt](media_mock)\n图片后";
        assertTrue(DingTalkClient.sendSingleMarkdownMessage("mock-token", "robot_mock", "user_mock",
                "图片标题", text, endpoint));
        Request request = lastRequest();
        assertEquals("POST", request.method);
        assertEquals("/v1.0/robot/oToMessages/batchSend", request.path);
        assertNull(request.query);
        assertEquals("mock-token", request.token);
        assertTrue(request.contentType.startsWith("application/json"));
        ONode body = ONode.ofJson(new String(request.body, StandardCharsets.UTF_8));
        assertEquals("robot_mock", body.get("robotCode").getString());
        assertEquals(1, body.get("userIds").getArray().size());
        assertEquals("user_mock", body.get("userIds").get(0).getString());
        assertEquals("sampleMarkdown", body.get("msgKey").getString());
        assertTrue(body.get("msgParam").isString());
        ONode param = ONode.ofJson(body.get("msgParam").getString());
        assertEquals("图片标题", param.get("title").getString());
        assertEquals(text, param.get("text").getString());
        assertEquals(2, param.getObject().size());
    }

    @Test
    void rejectsUploadHttpBusinessMalformedAndIncompleteResponsesWithoutLeakingSecrets() throws IOException {
        byte[] data = image("png");
        status = 503;
        response = "mock-token secret response";
        assertSafeUploadFailure(data);
        status = 200;
        for (String invalid : Arrays.asList("", "not json mock-token", "null", "[]", "{}",
                "{\"media_id\":\"x\"}", "{\"errcode\":null,\"media_id\":\"x\"}",
                "{\"errcode\":false,\"media_id\":\"x\"}", "{\"errcode\":\"bad\",\"media_id\":\"x\"}",
                "{\"errcode\":40001,\"errmsg\":\"mock-token secret response\"}",
                "{\"errcode\":0}", "{\"errcode\":0,\"media_id\":\" \"}",
                "{\"errcode\":0,\"media_id\":123}")) {
            response = invalid;
            assertSafeUploadFailure(data);
        }
    }

    @Test
    void validatesUploadParametersBeforeAnyRequest() throws IOException {
        byte[] png = image("png");
        assertThrows(IllegalArgumentException.class, () -> upload(null, "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload(new byte[0], "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload(new byte[1048577], "a.png"));
        assertThrows(IllegalArgumentException.class, () -> upload("GIF89a".getBytes(StandardCharsets.UTF_8), "a.png"));
        for (String filename : Arrays.asList("", " ", "dir/a.png", "dir\\a.png")) {
            assertThrows(IllegalArgumentException.class, () -> upload(png, filename));
        }
        assertThrows(IllegalArgumentException.class, () -> DingTalkClient.uploadImage(" ", png, "a.png", endpoint));

        assertTrue(requests.isEmpty());
    }

    @Test
    void acceptsExactOneMiBLimitWithoutReencoding() throws IOException {
        byte[] data = Arrays.copyOf(image("png"), 1048576);
        assertEquals("media_mock", upload(data, "limit.png"));
        assertTrue(new String(lastRequest().body, StandardCharsets.ISO_8859_1)
                .contains(new String(data, StandardCharsets.ISO_8859_1)));
    }

    @Test
    void handlesTransportFailureWithoutSensitiveCause() throws IOException {
        byte[] png = image("png");
        server.stop(0);
        assertSafeUploadFailure(png);

    }

    private void assertSafeUploadFailure(byte[] data) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> upload(data, "a.png"));
        assertFalse(error.toString().contains("mock-token"));
        assertFalse(error.toString().contains("secret response"));
        assertNull(error.getCause(), "异常链不能泄露带 token 的 URL 或响应原文");
    }

    private String upload(byte[] data, String filename) {
        return DingTalkClient.uploadImage("mock-token", data, filename, endpoint);
    }

    private Request lastRequest() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
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
        final String token;
        final String contentType;
        final byte[] body;

        Request(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            query = exchange.getRequestURI().getRawQuery();
            token = exchange.getRequestHeaders().getFirst("x-acs-dingtalk-access-token");
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
