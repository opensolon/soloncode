package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedSenderTest {
    @Test
    void emptyAndSinglePartKeepOriginalBehavior() {
        assertEquals(0, ChunkedSender.sendChunked(null, config(10), (chunk, part) -> {
            fail("不应发送空文本");
            return true;
        }).getTotalParts());
        assertEquals(0, ChunkedSender.sendChunked("", config(10), (chunk, part) -> false).getTotalParts());
        assertEquals("0123456789", send("0123456789", 10).get(0));
    }

    @Test
    void hardCutsLongSingleLineAndTrailingLine() {
        assertEquals(24, send(repeat("x", 400), 24).get(0).length());
        send("short\n" + repeat("x", 150), 24);
        send(repeat("x", 150) + "\n", 24);
    }

    @Test
    void neverSelectsLineEndBeyondCapacity() {
        List<String> chunks = send("1234567890\nabcdefghijk\nlast", 10);
        assertEquals("1234567890", chunks.get(0));
        chunks = send("abc\n12345678901234567890\ntail", 10);
        assertEquals("abc\n", chunks.get(0));
        chunks = send("123456789\nabcdefghijk", 10);
        assertEquals("123456789\n", chunks.get(0));
    }

    @Test
    void reservesActualPrefixLengthAcrossDigitBoundaries() {
        List<String> chunks = send(repeat("x", 800), 10);
        assertTrue(chunks.size() > 100);
        assertEquals(10, chunks.get(8).length());
        assertEquals(10, chunks.get(9).length());
        assertEquals(10, chunks.get(99).length());
        assertTrue(chunks.get(9).startsWith("(10) "));
        assertTrue(chunks.get(99).startsWith("(100) "));
    }

    @Test
    void protectsEveryInternalImageBoundary(@TempDir Path root) throws Exception {
        root = root.toRealPath();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", root.resolve("one.png").toFile());
        for (String token : new String[]{"![图](image_key_123)", "![图片](media_id_456)", "![\uD83D\uDE00](img_key)",
                "![a\\]b](image_key)", "![a\\[b\\]c\\]d](img_v3_key)",
                "![a\\*b\\(c\\)\\!](media_mock)",
                "![a\\]b\uD83D\uDE00](@lALabc_123-xyz)"}) {
            int maxLen = token.length() + 8;
            for (int offset = 1; offset < token.length(); offset++) {
                String text = repeat("x", maxLen - offset) + token + repeat("z", maxLen);
                List<String> chunks = sendPublished(text, token, root, maxLen);
                assertEquals(1, chunks.stream().filter(chunk -> chunk.contains(token)).count());
            }
            sendPublished(repeat("x", maxLen) + token + token, token, root, maxLen);
            sendPublished(repeat("x", maxLen - token.length()) + token + "tail", token, root, maxLen);
        }
    }

    @Test
    void protectsEscapedAltActuallyRenderedBySharedReply(@TempDir Path root) throws Exception {
        root = root.toRealPath();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", root.resolve("one.png").toFile());
        ChannelReply reply = ChannelReply.fromMarkdown("![a\\]b](one.png)", true, "run",
                root.toString(), "");
        String token = reply.renderImages(image -> "img_key");
        assertEquals("![a\\]b](img_key)", token);
        int maxLen = token.length() + 4;
        for (int offset = 1; offset < token.length(); offset++) {
            List<String> chunks = sendPublished(repeat("x", maxLen - offset) + token + repeat("z", maxLen), token, root, maxLen);
            assertEquals(1, chunks.stream().filter(chunk -> chunk.contains(token)).count());
        }
    }

    @Test
    void ordinaryImageTargetsAndCodeExamplesRemainSplittable() {
        for (String target : new String[]{"img_" + repeat("a", 100), "media_" + repeat("a", 100),
                "https://example.com/" + repeat("a", 100) + ".png",
                "https://example.com/a(b(c)).png", "https://example.com/a%28b%29.png",
                "relative/" + repeat("a", 100) + ".png", repeat("target", 30)}) {
            for (String alt : new String[]{"alt", "a\\]b"}) {
                String token = "![" + alt + "](" + target + ")";
                send(token, 16);
                send("`" + token + "`", 16);
                send("```markdown\n" + token + "\n```", 16);
                send("~~~markdown\n" + token + "\n~~~", 16);
                send("    " + token, 16);
            }
        }
    }

    @Test
    void renderedHttpsParenthesesAndEveryBoundaryStayWhole() {
        String source = "![a\\]b](https://example.com/a(b).png)";
        String token = "![a\\]b](https://example.com/a%28b%29.png)";
        int maxLen = token.length() + 4;
        for (int offset = 1; offset < token.length(); offset++) {
            String before = repeat("x", maxLen - offset);
            ChannelReply.RenderedImages rendered = ChannelReply.fromMarkdown(before + source + "tail",
                    true, "run", null, null).renderImageNodes(null, ChannelReply.ExternalImagePolicy.HTTPS_IMAGE);
            assertEquals(before + token + "tail", rendered.getText());
            List<String> chunks = sendNodes(rendered, maxLen, rendered.getText());
            assertEquals(1, chunks.stream().filter(chunk -> chunk.contains(token)).count());
        }
    }

    @Test
    void imageExactlyFitsNumberedPart(@TempDir Path root) throws Exception {
        root = root.toRealPath();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", root.resolve("one.png").toFile());
        String token = "![alt](media_id)";
        int maxLen = token.length() + 4;
        List<String> chunks = sendPublished(repeat("x", maxLen) + token, token, root, maxLen);
        assertEquals("(2) " + token, chunks.get(1));
        assertEquals(maxLen, chunks.get(1).length());
    }

    @Test
    void oversizedNodesDegradeBeforeSendingAndLeaveOtherImagesWhole() {
        String longNode = "![long](https://example.com/" + repeat("a", 100) + ")";
        String shortNode = "![ok](https://example.com/b)";
        for (String before : new String[]{"", repeat("x", 40), repeat("line\n", 12)}) {
            ChannelReply reply = ChannelReply.fromMarkdown(before + longNode + " " + shortNode,
                    true, "run", null, null);
            ChannelReply.RenderedImages nodes = reply.renderImageNodes(null, ChannelReply.ExternalImagePolicy.HTTPS_IMAGE);
            String expected = before + reply.getImageReferences().get(0).getFallback() + " " + shortNode;
            List<String> sent = sendNodes(nodes, 40, expected);
            assertEquals(1, sent.stream().filter(chunk -> chunk.contains(shortNode)).count());
            assertFalse(String.join("", sent).contains("/aaa"));
            // 规划不得修改渲染快照，重试仍得到相同降级结果。
            assertEquals(before + longNode + " " + shortNode, nodes.getText());
            assertEquals(sent, sendNodes(nodes, 40, expected));
        }
    }

    @Test
    void numberedCapacityAndMultiDigitPrefixTriggerSafeDowngrade() {
        String token = "![图](https://example.com/a)";
        int maxLen = token.length() + 3;
        ChannelReply reply = ChannelReply.fromMarkdown(repeat("x", maxLen) + token, true, "run", null, null);
        sendNodes(reply.renderImageNodes(null, ChannelReply.ExternalImagePolicy.HTTPS_IMAGE), maxLen,
                repeat("x", maxLen) + reply.getImageReferences().get(0).getFallback());
        maxLen = token.length() + 4;
        // 每个完整行恰为一段，真实图片落在第 10 段，前缀增加到 5 个单元。
        String before = repeat(repeat("x", maxLen - 5) + "\n", 9);
        reply = ChannelReply.fromMarkdown(before + token, true, "run", null, null);
        sendNodes(reply.renderImageNodes(null, ChannelReply.ExternalImagePolicy.HTTPS_IMAGE), maxLen,
                before + reply.getImageReferences().get(0).getFallback());
    }

    @Test
    void keepsSurrogatePairsWholeAtFirstAndNumberedBoundaries() {
        String emoji = "\uD83D\uDE00";
        List<String> chunks = send(repeat("a", 9) + emoji + repeat("b", 30), 10);
        assertEquals(repeat("a", 9), chunks.get(0));
        chunks = send(repeat("a", 15) + emoji + repeat("b", 30), 10);
        assertEquals("(2) " + repeat("a", 5), chunks.get(1));
        send(repeat(emoji, 60), 11);
    }

    @Test
    void rejectsCapacityTooSmallForPrefixOrSurrogatePair() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkedSender.sendChunked("12345", config(4), (chunk, part) -> true));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkedSender.sendChunked("12345\uD83D\uDE00", config(5), (chunk, part) -> true));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkedSender.sendChunked("\uD83D\uDE00", config(1), (chunk, part) -> true));
    }

    @Test
    void codeAndTablePreferencesNeverOverrideHardLimit() {
        send("intro\n```java\n" + repeat("x", 100) + "\n```\nend", 24);
        send("| a | b |\n|---|---|\n" + repeat("| c | d |\n", 20) + "\nend", 24);
        send("ordinary [link](target) and ![unfinished " + repeat("x", 100), 24);
    }

    @Test
    void retriesReusePayloadAndIndexAndContinueAfterFailure() {
        List<String> attempts = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChunkedSender.SendResult result = ChunkedSender.sendChunked("1234567890abcdef",
                new ChunkedSender.Config(10, 0, 2, 0), (chunk, part) -> {
                    attempts.add(chunk);
                    indexes.add(part);
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("temporary");
                    }
                    return part != 1;
                });
        assertEquals(2, result.getTotalParts());
        assertEquals(1, result.getFailedParts());
        assertFalse(result.allSucceeded());
        assertEquals(4, attempts.size());
        assertEquals(attempts.get(0), attempts.get(1));
        assertEquals(attempts.get(1), attempts.get(2));
        assertEquals(Integer.valueOf(1), indexes.get(2));
        assertEquals(Integer.valueOf(2), indexes.get(3));
    }

    @Test
    void interruptedIntervalStillStopsBeforeNextSend() {
        AtomicInteger calls = new AtomicInteger();
        try {
            ChunkedSender.SendResult result = ChunkedSender.sendChunked("1234567890abcdef",
                    new ChunkedSender.Config(10, 1, 0, 0), (chunk, part) -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        return true;
                    });
            assertEquals(1, calls.get());
            assertEquals(1, result.getTotalParts());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static List<String> sendPublished(String text, String token, Path root, int maxLen) {
        int targetStart = token.lastIndexOf("](") + 2;
        String target = token.substring(targetStart, token.length() - 1);
        String source = text.replace(token, token.substring(0, targetStart) + "one.png)");
        ChannelReply.RenderedImages nodes = ChannelReply.fromMarkdown(source, true, "run", root.toString(), "")
                .renderImageNodes(image -> target, ChannelReply.ExternalImagePolicy.BLOCK);
        return sendNodes(nodes, maxLen, text);
    }

    private static List<String> sendNodes(ChannelReply.RenderedImages nodes, int maxLen, String expected) {
        List<String> chunks = new ArrayList<>();
        StringBuilder restored = new StringBuilder();
        ChunkedSender.SendResult result = ChunkedSender.sendImageNodes(nodes, config(maxLen), (chunk, part) -> {
            assertEquals(chunks.size() + 1, part);
            assertTrue(chunk.length() <= maxLen);
            String body = chunk.substring(part == 1 ? 0 : ("(" + part + ") ").length());
            assertFalse(body.isEmpty());
            assertFalse(Character.isLowSurrogate(body.charAt(0)));
            assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)));
            restored.append(body);
            chunks.add(chunk);
            return true;
        });
        assertEquals(expected, restored.toString());
        assertEquals(chunks.size(), result.getTotalParts());
        assertTrue(result.allSucceeded());
        return chunks;
    }

    private static ChunkedSender.Config config(int maxLen) {
        return new ChunkedSender.Config(maxLen, 0, 0, 0);
    }

    private static List<String> send(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        StringBuilder restored = new StringBuilder();
        ChunkedSender.SendResult result = ChunkedSender.sendChunked(text, config(maxLen), (chunk, part) -> {
            assertEquals(chunks.size() + 1, part);
            assertTrue(chunk.length() <= maxLen, "消息包含序号后仍须限长");
            String prefix = part == 1 ? "" : "(" + part + ") ";
            assertTrue(chunk.startsWith(prefix));
            String body = chunk.substring(prefix.length());
            assertFalse(body.isEmpty());
            assertFalse(Character.isLowSurrogate(body.charAt(0)));
            assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)));
            restored.append(body);
            chunks.add(chunk);
            return true;
        });
        assertEquals(text, restored.toString());
        assertEquals(chunks.size(), result.getTotalParts());
        assertTrue(result.allSucceeded());
        return chunks;
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
