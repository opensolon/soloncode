package org.noear.solon.codecli.channel.feishu;

import org.noear.solon.codecli.artifact.PublishedImage;
import org.noear.solon.codecli.channel.ChannelReply;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实文件准备不可变快照；返回回复前即删除源文件，异步发送只能使用快照。 */
final class FeishuImageFixtures {
    private FeishuImageFixtures() {
    }

    static ChannelReply reply(String text, PublishedImage... images) {
        StringBuilder body = new StringBuilder(text == null ? "" : text);
        for (int i = 0; i < images.length; i++) {
            if (body.length() > 0) body.append('\n');
            body.append("{{").append(i + 1).append("}}");
        }
        return inPlaceReply(body.toString(), images);
    }

    /** {{1}} 等标记替换为真实 Markdown 引用，相同对象始终复用同一个文件路径。 */
    static ChannelReply inPlaceReply(String text, PublishedImage... images) {
        List<Path> cleanup = new ArrayList<>();
        try {
            Path root = Files.createTempDirectory(Paths.get("."), "feishu-images-").toRealPath();
            cleanup.add(root);
            Path directory = Files.createDirectory(root.resolve("private"));
            cleanup.add(directory);
            Map<PublishedImage, String> paths = new IdentityHashMap<>();
            String body = text;
            for (int i = 0; i < images.length; i++) {
                PublishedImage image = images[i];
                String relative = paths.get(image);
                if (relative == null) {
                    relative = "private/local" + i + ".png";
                    paths.put(image, relative);
                    if (image != null) {
                        Path file = root.resolve(relative);
                        cleanup.add(file);
                        Files.write(file, image.getBytes());
                    }
                }
                body = body.replace("{{" + (i + 1) + "}}", "![图" + (i + 1) + "](" + relative + ")");
            }
            ChannelReply reply = ChannelReply.fromMarkdown(body, true, "run", root.toString(), "");
            assertEquals(images.length, reply.getImageReferences().size());
            Map<PublishedImage, Integer> order = new IdentityHashMap<>();
            for (int i = 0; i < images.length; i++) {
                PublishedImage source = images[i];
                PublishedImage snapshot = reply.getImageReferences().get(i).getImage();
                if (source == null) {
                    assertNull(snapshot, "缺失文件只能生成安全占位");
                    continue;
                }
                if (!order.containsKey(source)) order.put(source, order.size() + 1);
                if (order.get(source) <= 5) {
                    assertNotNull(snapshot, "真实图片必须成功加载为快照");
                    assertArrayEquals(source.getBytes(), snapshot.getBytes());
                } else {
                    assertNull(snapshot, "超出五图预算的引用不得加载");
                }
            }
            assertEquals(Math.min(5, order.size()), reply.getImages().size());
            return reply;
        } catch (IOException e) {
            throw new AssertionError("无法准备真实图片 fixture", e);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(cleanup.get(i));
                } catch (IOException e) {
                    throw new AssertionError("无法清理图片 fixture", e);
                }
            }
        }
    }

    static PublishedImage image(int id) {
        return image(id, "private/" + id + ".png", null);
    }

    static PublishedImage image(int id, String name, String caption) {
        return encodedImage(id, name, caption, "png");
    }

    static PublishedImage jpeg(int id, String name) {
        return encodedImage(id, name, null, "jpeg");
    }

    private static PublishedImage encodedImage(int id, String name, String caption, String format) {
        assertTrue(id >= 1 && id <= 6);
        BufferedImage pixels = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        // 灰度以 32 为间隔，JPEG 有损编码后仍能稳定识别编号。
        int gray = id * 32;
        int rgb = (gray << 16) | (gray << 8) | gray;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) pixels.setRGB(x, y, rgb);
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(pixels, format, bytes));
            return new PublishedImage(bytes.toByteArray(), name, caption);
        } catch (IOException e) {
            throw new AssertionError("无法编码 fixture 图片", e);
        } finally {
            pixels.flush();
        }
    }

    static int imageId(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage pixels = ImageIO.read(input);
            assertNotNull(pixels, "上传数据必须是可解码的真实图片");
            try {
                return Math.round(((pixels.getRGB(0, 0) >> 16) & 0xff) / 32.0f);
            } finally {
                pixels.flush();
            }
        } catch (IOException e) {
            throw new AssertionError("无法解码上传图片", e);
        }
    }
}
