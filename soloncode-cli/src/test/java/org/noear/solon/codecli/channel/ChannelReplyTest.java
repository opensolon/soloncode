package org.noear.solon.codecli.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.codecli.artifact.PublishedImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.noear.solon.codecli.channel.ChannelReply.ExternalImagePolicy.*;

class ChannelReplyTest {
    @TempDir
    Path temp;

    @Test
    void factoryExposesImmutableSnapshotsAndPreservesReplyMetadata() throws Exception {
        writePng("one.png");
        String markdown = "text ![one](one.png) ![again](./one.png)";
        ChannelReply reply = reply(markdown);

        assertEquals(markdown, reply.getText());
        assertTrue(reply.isFinal());
        assertEquals("run", reply.getRunId());
        assertEquals(1, reply.getImages().size());
        assertEquals(2, reply.getImageReferences().size());
        PublishedImage image = reply.getImages().get(0);
        assertSame(image, reply.getImageReferences().get(0).getImage());
        assertSame(image, reply.getImageReferences().get(1).getImage());
        assertThrows(UnsupportedOperationException.class, () -> reply.getImages().clear());
        assertThrows(UnsupportedOperationException.class, () -> reply.getImageReferences().clear());
        ChannelReply empty = ChannelReply.fromMarkdown(null, false, null, null, null);
        assertNull(empty.getText());
        assertNull(empty.getRunId());
        assertFalse(empty.isFinal());
        assertTrue(empty.getImages().isEmpty());
        assertTrue(empty.getImageReferences().isEmpty());
    }

    @Test
    void nonFeishuChannelFallsBackToRouteAwareTextWithoutChangingRouteOrFinalFlag() throws Exception {
        writePng("one.png");
        TextChannel channel = new TextChannel();
        channel.sendReply("session", reply("answer ![one](one.png)"), "source", "target", "message");

        assertEquals(1, channel.calls);
        assertEquals("answer （图片不可用：one；上传失败或渠道不支持）", channel.text);
        assertEquals(Arrays.asList("session", "source", "target", "message"), channel.route);
        assertTrue(channel.isFinal);

        channel.sendReply("session", ChannelReply.fromMarkdown("stream", false, "run", null, null),
                "source", "target", "message");
        assertEquals(2, channel.calls);
        assertEquals("stream", channel.text);
        assertFalse(channel.isFinal);
    }

    @Test
    void pureImageFallbackIsExplicitAndNeverSendsEmptyText() throws Exception {
        writePng("one.png");
        TextChannel channel = new TextChannel();
        channel.sendReply("session", reply("![one](one.png)"), null, null, null);
        assertEquals(1, channel.calls);
        assertEquals("（图片不可用：one；上传失败或渠道不支持）", channel.text);
        assertFalse(channel.text.contains("Web"));
        assertTrue(channel.isFinal);

        channel.sendReply("session", reply(""), null, null, null);
        channel.sendReply("session", reply(null), null, null, null);
        channel.sendReply("session", (ChannelReply) null, null, null, null);
        assertEquals(1, channel.calls);
    }

    @Test
    void standardAstIncludesReferencesButExcludesCodeEscapesAndHtml() {
        String markdown = "😀 ![one](a.png) ![two][KEY] ![short][] ![shortcut]\n\n"
                + "[key]: b.png\n[short]: c.png\n[shortcut]: d.png\n\n"
                + "`![inline](secret.png)`\n\n```\n![fenced](secret.png)\n```\n\n"
                + "    ![indented](secret.png)\n\n\\![escaped](secret.png) <img src=\"secret.png\">";
        ChannelReply reply = ChannelReply.fromMarkdown(markdown, true, "run", null, null, false);
        assertEquals(4, reply.getImageReferences().size());
        assertTrue(reply.hasImageReferences());
        assertTrue(reply.getImages().isEmpty());
        assertEquals(3, reply.getImageReferences().get(0).getStart());
        assertEquals("![one](a.png)", markdown.substring(reply.getImageReferences().get(0).getStart(),
                reply.getImageReferences().get(0).getEnd()));
        assertEquals("short", reply.getImageReferences().get(2).getAlt());
        assertEquals("shortcut", reply.getImageReferences().get(3).getAlt());
        assertNull(reply.getImageReferences().get(0).getImage());
        String rendered = reply.renderImages(noPublisher());
        assertTrue(rendered.contains("（图片不可用：one；图片发送已禁用）"));
        assertTrue(rendered.contains("`![inline](secret.png)`"));
        assertTrue(rendered.contains("![fenced](secret.png)"));
        assertTrue(rendered.contains("    ![indented](secret.png)"));
        assertTrue(rendered.contains("\\![escaped](secret.png)"));
        assertTrue(rendered.contains("<img src=\"secret.png\">"));
        assertFalse(rendered.contains("![one](a.png)"));
        assertFalse(rendered.contains("[key]:"));
        assertFalse(rendered.contains("[short]:"));
        assertFalse(rendered.contains("[shortcut]:"));
        assertEquals(markdown, reply.getText());
    }

    @Test
    void sharedDefinitionPreservesExplicitOrdinaryLinksWhenImagesAreRendered() throws Exception {
        writePng("one.png");
        String links = "[下载][id] [ID][] [id]";
        String definition = "\n\n[id]: one.png \"下载原图\"";
        String markdown = "![图][id] " + links + definition;
        ChannelReply reply = reply(markdown);

        assertEquals(1, reply.getImageReferences().size());
        assertEquals("![图](published) " + links + definition,
                reply.renderImages(image -> "published"));
        assertEquals("（图片不可用：图；上传失败或渠道不支持） " + links + definition,
                reply.renderImages(null));
        assertEquals("（图片不可用：图；图片发送已禁用） " + links + definition,
                reply.withoutImages().renderImages(noPublisher()));
        ChannelReply disabled = ChannelReply.fromMarkdown(markdown, true, "run", null, null, false);
        assertEquals("（图片不可用：图；图片发送已禁用） " + links + definition,
                disabled.renderImages(noPublisher()));
        assertEquals(markdown, reply.getText());
    }

    @Test
    void successfulSnapshotsAreUniqueAndRenderedAtTheirOriginalPositions() throws Exception {
        Path file = writePng("one.png");
        String markdown = "before ![a\\]b](one.png) middle ![again][id] after ![bad](../secret.png)\n\n[id]: ./one.png";
        ChannelReply reply = reply(markdown);
        assertEquals(3, reply.getImageReferences().size());
        assertEquals(1, reply.getImages().size());
        assertSame(reply.getImageReferences().get(0).getImage(), reply.getImageReferences().get(1).getImage());
        Files.delete(file);
        AtomicInteger calls = new AtomicInteger();
        String rendered = reply.renderImages(image -> {
            assertSame(reply.getImages().get(0), image);
            calls.incrementAndGet();
            return "platform-target";
        });
        assertEquals(1, calls.get());
        assertEquals("before ![a\\]b](platform-target) middle ![again](platform-target) after "
                + "（图片不可用：bad；读取失败或目标不安全）\n\n", rendered);
        assertEquals(markdown, reply.getText());
        assertTrue(reply.getImages().get(0).byteSize() > 0);
    }

    @Test
    void failedPublishersUseFixedReasonsAndNeverEchoExceptionsOrPaths() throws Exception {
        writePng("one.png");
        ChannelReply reply = reply("![safe](one.png) ![again](./one.png)");
        String expected = "（图片不可用：safe；上传失败或渠道不支持） （图片不可用：again；上传失败或渠道不支持）";
        assertEquals(expected, reply.renderImages(null));
        assertEquals(expected, reply.renderImages(image -> null));
        assertEquals(expected, reply.renderImages(image -> "  "));
        AtomicInteger calls = new AtomicInteger();
        assertEquals(expected, reply.renderImages(image -> {
            calls.incrementAndGet();
            throw new IllegalStateException("/private/one.png?token=publisher-secret");
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void externalPoliciesRenderOnlyPermittedTargetsWithoutCallingPublisher() throws Exception {
        ChannelReply reply = reply("![secure](https://example.com/one.png) ![plain](http://example.com/two.png)");
        assertTrue(reply.getImages().isEmpty());
        assertEquals(2, reply.getImageReferences().size());
        assertNull(reply.getImageReferences().get(0).getImage());
        assertEquals("https://example.com/one.png", reply.getImageReferences().get(0).getExternalUrl());
        assertEquals("http://example.com/two.png", reply.getImageReferences().get(1).getExternalUrl());
        String blocked = "（图片不可用：secure；当前渠道未启用外链图片） （图片不可用：plain；当前渠道未启用外链图片）";
        assertEquals(blocked, reply.renderImages(noPublisher()));
        assertEquals(blocked, reply.renderImages(noPublisher(), BLOCK));
        assertEquals("![secure](https://example.com/one.png) （图片不可用：plain；当前渠道未启用外链图片）",
                reply.renderImages(noPublisher(), HTTPS_IMAGE));
        assertEquals("[secure](https://example.com/one.png) [plain](http://example.com/two.png)",
                reply.renderImages(noPublisher(), HTTP_LINK));
    }

    @Test
    void localMarkdownTargetsDecodeOnceAndStripOnlyRawFragments() throws Exception {
        writePng("my chart.png");
        writePng("中文+%.png");
        writePng("chart#preview.png");
        writePng("my%20chart.png");
        String markdown = "![space](my%20chart.png#preview) ![same][id] "
                + "![unicode](%E4%B8%AD%E6%96%87+%25.png) ![hash](chart%23preview.png) "
                + "![once](my%2520chart.png)\n\n[id]: my%20chart.png#other";
        ChannelReply reply = reply(markdown);
        assertEquals(5, reply.getImageReferences().size());
        assertEquals(4, reply.getImages().size());
        assertSame(reply.getImageReferences().get(0).getImage(), reply.getImageReferences().get(1).getImage());
        assertEquals("my chart.png", reply.getImages().get(0).getFilename());
        assertEquals("中文+%.png", reply.getImages().get(1).getFilename());
        assertEquals("chart#preview.png", reply.getImages().get(2).getFilename());
        assertEquals("my%20chart.png", reply.getImages().get(3).getFilename());
        AtomicInteger calls = new AtomicInteger();
        assertEquals("![space](published) ![same](published) ![unicode](published) ![hash](published) "
                + "![once](published)\n\n", reply.renderImages(image -> {
            calls.incrementAndGet();
            return "published";
        }));
        assertEquals(4, calls.get());
    }

    @Test
    void encodedLocalBypassesAndNonHttpSchemesStayBlockedInMarkdown() throws Exception {
        writePng("chart.png");
        Files.createDirectory(temp.resolve("sub"));
        writePng("sub/chart.png");
        for (String target : Arrays.asList("sub/%2e%2e/chart.png", "sub%2fchart.png", "sub%5Cchart.png",
                "%FF.png", "%GG.png", "file:chart.png", "data:image/png;base64,AA==", "//host/chart.png")) {
            ChannelReply reply = reply("![safe](" + target + ")");
            assertEquals(1, reply.getImageReferences().size(), target);
            assertTrue(reply.getImages().isEmpty(), target);
            assertNull(reply.getImageReferences().get(0).getExternalUrl(), target);
            assertEquals("（图片不可用：safe；读取失败或目标不安全）", reply.renderImages(noPublisher()), target);
        }
    }

    @Test
    void externalEncodedTargetsNeverUseLocalDecoderOrPublisher() {
        // 连非法 UTF-8 percent 字节也仍是 URI 外链，不适用本地解码规则。
        for (String url : Arrays.asList("https://example.com/my%20chart+one.png",
                "http://example.com/%2e%2e%2fchart.png", "https://example.com/%FF.png",
                "https://example.com/%2520chart.png")) {
            ChannelReply reply = ChannelReply.fromMarkdown("![remote](" + url + ")", true, "run", null, null);
            assertTrue(reply.getImages().isEmpty());
            assertEquals(1, reply.getImageReferences().size());
            assertEquals(url, reply.getImageReferences().get(0).getExternalUrl());
            assertEquals("[remote](" + url + ")", reply.renderImages(noPublisher(), HTTP_LINK));
        }
    }

    @Test
    void externalPoliciesDoNotChangeLocalPublishing() throws Exception {
        writePng("one.png");
        ChannelReply reply = reply("![local](one.png) ![remote](https://example.com/image.png)");
        for (ChannelReply.ExternalImagePolicy policy : ChannelReply.ExternalImagePolicy.values()) {
            AtomicInteger calls = new AtomicInteger();
            String rendered = reply.renderImages(image -> {
                calls.incrementAndGet();
                return "platform-target";
            }, policy);
            assertEquals(1, calls.get());
            assertTrue(rendered.startsWith("![local](platform-target) "));
        }
    }

    @Test
    void queryUserInfoAndFragmentUrlsAreRejectedUnderEveryPolicy() throws Exception {
        for (String url : Arrays.asList("https://example.com/image.png?token=private-token",
                "http://example.com/image.png?download=1", "https://user:password@example.com/image.png",
                "http://user@example.com/image.png", "https://example.com/image.png#private-fragment")) {
            ChannelReply reply = reply("![safe](" + url + ")");
            assertEquals(1, reply.getImageReferences().size());
            assertNull(reply.getImageReferences().get(0).getExternalUrl());
            assertNull(reply.getImageReferences().get(0).getImage());
            assertTrue(reply.getImages().isEmpty());
            for (ChannelReply.ExternalImagePolicy policy : ChannelReply.ExternalImagePolicy.values()) {
                assertEquals("（图片不可用：safe；外链不安全）", reply.renderImages(noPublisher(), policy));
            }
        }
    }

    @Test
    void disabledImagesAndWithoutImagesRevokeLocalSnapshotsAndExternalUrls() throws Exception {
        writePng("one.png");
        String markdown = "![local](one.png) ![remote][id]\n\n[id]: https://example.com/image.png";
        ChannelReply original = reply(markdown);
        ChannelReply disabled = ChannelReply.fromMarkdown(markdown, true, "run", temp.toRealPath().toString(), "", false);
        ChannelReply revoked = original.withoutImages();
        for (ChannelReply safe : Arrays.asList(disabled, revoked, revoked.withoutImages())) {
            assertEquals(markdown, safe.getText());
            assertEquals("run", safe.getRunId());
            assertTrue(safe.isFinal());
            assertEquals(2, safe.getImageReferences().size());
            assertTrue(safe.getImages().isEmpty());
            for (ChannelReply.ImageReference reference : safe.getImageReferences()) {
                assertNull(reference.getImage());
                assertNull(reference.getExternalUrl());
            }
            for (ChannelReply.ExternalImagePolicy policy : ChannelReply.ExternalImagePolicy.values()) {
                assertEquals("（图片不可用：local；图片发送已禁用） （图片不可用：remote；图片发送已禁用）\n\n",
                        safe.renderImages(noPublisher(), policy));
            }
        }
        assertEquals(1, original.getImages().size());
        assertNotNull(original.getImageReferences().get(1).getExternalUrl());
    }

    @Test
    void sensitiveAltPathsAndTokensNeverAppearInFallbackOrExpandedDefinitions() throws Exception {
        for (String alt : Arrays.asList("/private/secret.png", "token=private-token", "password",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", "api_key")) {
            String markdown = "![" + alt + "][id]\n\n[id]: ../private/image.png?token=private-token";
            ChannelReply reply = reply(markdown);
            assertEquals("", reply.getImageReferences().get(0).getAlt());
            assertEquals("（图片不可用；读取失败或目标不安全）\n\n", reply.renderImages(noPublisher()));
            assertEquals(markdown, reply.getText());
        }
        ChannelReply external = reply("![token=private-token][id]\n\n[id]: https://example.com/image.png?token=private-token");
        assertEquals("（图片不可用；外链不安全）\n\n", external.renderImages(noPublisher(), HTTP_LINK));

        writePng("one.png");
        ChannelReply local = reply("![password](one.png)");
        assertEquals("![图片](platform-target)", local.renderImages(image -> "platform-target"));
        assertEquals("（图片不可用；上传失败或渠道不支持）", local.renderImages(image -> null));
    }

    @Test
    void readFormatAndImageCountFailuresHaveDistinctFixedReasons() throws Exception {
        assertEquals("（图片不可用：missing；读取失败或目标不安全）",
                reply("![missing](missing.png)").renderImages(noPublisher()));
        assertEquals("（图片不可用：unsafe；读取失败或目标不安全）",
                reply("![unsafe](../private.png)").renderImages(noPublisher()));

        // 使用真实 PNG 的截断头稳定触发不可识别格式，不依赖解码器异常消息。
        byte[] png = Files.readAllBytes(writePng("valid.png"));
        Files.write(temp.resolve("broken.png"), Arrays.copyOf(png, 4));
        assertEquals("（图片不可用：broken；格式不支持或图片损坏）",
                reply("![broken](broken.png)").renderImages(noPublisher()));
        Path gif = temp.resolve("unsupported.gif");
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", gif.toFile()));
        assertEquals("（图片不可用：unsupported；格式不支持或图片损坏）",
                reply("![unsupported](unsupported.gif)").renderImages(noPublisher()));

        StringBuilder markdown = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            writePng("image" + i + ".png");
            markdown.append("![image").append(i).append("](image").append(i).append(".png) ");
        }
        ChannelReply limited = reply(markdown.toString());
        assertEquals(5, limited.getImages().size());
        assertEquals(6, limited.getImageReferences().size());
        assertNull(limited.getImageReferences().get(5).getImage());
        assertEquals("（图片不可用：image5；超过图片限制）", limited.getImageReferences().get(5).getFallback());
        assertTrue(limited.renderImages(image -> "platform-target").endsWith("（图片不可用：image5；超过图片限制） "));
    }

    @Test
    void nonFinalAndUnresolvedReferencesAreNotPrepared() {
        String text = "![image](one.png) ![undefined][missing]";
        ChannelReply streaming = ChannelReply.fromMarkdown(text, false, "run", null, null);
        assertFalse(streaming.hasImageReferences());
        assertEquals(text, streaming.renderImages(noPublisher()));
        ChannelReply terminal = ChannelReply.fromMarkdown("![undefined][missing]", true, "run", null, null);
        assertFalse(terminal.hasImageReferences());
        assertEquals("![undefined][missing]", terminal.renderImages(noPublisher()));
    }

    @Test
    void renderedImageNodesIgnoreCodeIndentedAndEscapedPlatformLookalikes() throws Exception {
        String pseudo = "```markdown\n![fenced](img_fake) ![fenced](media_fake)\n```\n\n"
                + "`![inline](img_fake) ![inline](media_fake)`\n\n"
                + "    ![indented](img_fake) ![indented](media_fake)\n\n"
                + "\\![escaped](img_fake) \\![escaped](media_fake) img_plain media_plain\n\n";
        ChannelReply.RenderedImages untouched = reply(pseudo).renderImageNodes(noPublisher(), BLOCK);
        assertEquals(pseudo, untouched.getText());
        assertTrue(untouched.getRanges().isEmpty());

        writePng("one.png");
        for (String target : Arrays.asList("img_real", "media_real", "unprefixed-target")) {
            ChannelReply.RenderedImages rendered = reply(pseudo + "![real](one.png)")
                    .renderImageNodes(image -> target, BLOCK);
            String node = "![real](" + target + ")";
            assertEquals(pseudo + node, rendered.getText());
            assertEquals(1, rendered.getRanges().size());
            assertRenderedRange(rendered, 0, pseudo.length(), node,
                    "（图片不可用：real；上传失败或渠道不支持）");
        }
    }

    @Test
    void renderedImageNodesTrackOutputOffsetsAfterDefinitionsLengthChangesAndEscapedEmojiAlt() throws Exception {
        writePng("one.png");
        String markdown = "[first]: one.png\n\n😀 ![a\\]😀*b][first] middle ![bad](../missing.png)\n\n"
                + "[second]: ./one.png\n\n![again][second] end";
        ChannelReply reply = reply(markdown);
        String first = "![a\\]😀\\*b](media_a_much_longer_published_target)";
        String second = "![again](media_a_much_longer_published_target)";
        String prefix = "\n\n😀 ";
        String between = " middle （图片不可用：bad；读取失败或目标不安全）\n\n\n\n";
        AtomicInteger calls = new AtomicInteger();
        ChannelReply.RenderedImages rendered = reply.renderImageNodes(image -> {
            calls.incrementAndGet();
            return "media_a_much_longer_published_target";
        }, BLOCK);

        assertEquals(prefix + first + between + second + " end", rendered.getText());
        assertEquals(1, calls.get());
        assertEquals(2, rendered.getRanges().size());
        assertRenderedRange(rendered, 0, prefix.length(), first,
                "（图片不可用：a\\]😀\\*b；上传失败或渠道不支持）");
        assertRenderedRange(rendered, 1, prefix.length() + first.length() + between.length(), second,
                "（图片不可用：again；上传失败或渠道不支持）");
        assertFalse(rendered.getText().contains("[first]:"));
        assertFalse(rendered.getText().contains("[second]:"));
        assertEquals(markdown, reply.getText());
    }

    @Test
    void renderedImageNodesIncludeHttpLinksButNeverOrdinaryLinksOrBlockedFallbacks() throws Exception {
        String ordinary = "[ordinary](https://example.com/ordinary.png) ";
        ChannelReply reply = reply(ordinary + "![secure](https://example.com/one.png) "
                + "![plain](http://example.com/two.png)");
        String secureLink = "[secure](https://example.com/one.png)";
        String plainLink = "[plain](http://example.com/two.png)";
        String secureFallback = "（图片不可用：secure；当前渠道未启用外链图片）";
        String plainFallback = "（图片不可用：plain；当前渠道未启用外链图片）";
        ChannelReply.RenderedImages links = reply.renderImageNodes(noPublisher(), HTTP_LINK);
        assertEquals(ordinary + secureLink + " " + plainLink, links.getText());
        assertEquals(2, links.getRanges().size());
        assertRenderedRange(links, 0, ordinary.length(), secureLink, secureFallback);
        assertRenderedRange(links, 1, ordinary.length() + secureLink.length() + 1, plainLink, plainFallback);

        ChannelReply.RenderedImages https = reply.renderImageNodes(noPublisher(), HTTPS_IMAGE);
        String secureImage = "!" + secureLink;
        assertEquals(ordinary + secureImage + " " + plainFallback, https.getText());
        assertEquals(1, https.getRanges().size());
        assertRenderedRange(https, 0, ordinary.length(), secureImage, secureFallback);
        ChannelReply.RenderedImages blocked = reply.renderImageNodes(noPublisher(), BLOCK);
        assertEquals(ordinary + secureFallback + " " + plainFallback, blocked.getText());
        assertTrue(blocked.getRanges().isEmpty());
    }

    @Test
    void renderedImageNodesPublishRepeatedSnapshotsOnceAndKeepSeparateRanges() throws Exception {
        writePng("one.png");
        ChannelReply reply = reply("![one](one.png)![two](./one.png)");
        AtomicInteger calls = new AtomicInteger();
        ChannelReply.RenderedImages rendered = reply.renderImageNodes(image -> {
            calls.incrementAndGet();
            assertSame(reply.getImages().get(0), image);
            return "img_shared";
        }, BLOCK);
        String first = "![one](img_shared)";
        String second = "![two](img_shared)";
        assertEquals(first + second, rendered.getText());
        assertEquals(1, calls.get());
        assertEquals(2, rendered.getRanges().size());
        assertRenderedRange(rendered, 0, 0, first, "（图片不可用：one；上传失败或渠道不支持）");
        assertRenderedRange(rendered, 1, first.length(), second, "（图片不可用：two；上传失败或渠道不支持）");
    }

    @Test
    void renderedImageNodesFallbacksHaveNoRangesAndFailedPublishersAreDeduplicated() throws Exception {
        writePng("one.png");
        ChannelReply reply = reply("![one](one.png) ![again](./one.png) ![bad](../missing.png)");
        String expected = "（图片不可用：one；上传失败或渠道不支持） "
                + "（图片不可用：again；上传失败或渠道不支持） （图片不可用：bad；读取失败或目标不安全）";
        ChannelReply.RenderedImages absent = reply.renderImageNodes(null, BLOCK);
        assertEquals(expected, absent.getText());
        assertTrue(absent.getRanges().isEmpty());
        for (String target : Arrays.asList(null, "", "  ", "throw")) {
            AtomicInteger calls = new AtomicInteger();
            ChannelReply.RenderedImages rendered = reply.renderImageNodes(image -> {
                calls.incrementAndGet();
                if ("throw".equals(target)) {
                    throw new IllegalStateException("publisher-private-token");
                }
                return target;
            }, BLOCK);
            assertEquals(expected, rendered.getText());
            assertTrue(rendered.getRanges().isEmpty());
            assertEquals(1, calls.get());
        }
        ChannelReply.RenderedImages disabled = reply.withoutImages().renderImageNodes(noPublisher(), HTTP_LINK);
        assertEquals("（图片不可用：one；图片发送已禁用） （图片不可用：again；图片发送已禁用） "
                + "（图片不可用：bad；图片发送已禁用）", disabled.getText());
        assertTrue(disabled.getRanges().isEmpty());
    }

    @Test
    void renderedImageNodeRangesAreImmutableSnapshotsIncludingEmptyResults() throws Exception {
        writePng("one.png");
        ChannelReply reply = reply("![one](one.png)");
        ChannelReply.RenderedImages first = reply.renderImageNodes(image -> "img_first", BLOCK);
        List<ChannelReply.ImageRange> ranges = first.getRanges();
        assertEquals(1, ranges.size());
        ChannelReply.ImageRange range = ranges.get(0);
        assertThrows(UnsupportedOperationException.class, () -> ranges.add(range));
        assertThrows(UnsupportedOperationException.class, () -> ranges.set(0, range));
        assertThrows(UnsupportedOperationException.class, () -> ranges.remove(0));
        assertThrows(UnsupportedOperationException.class, ranges::clear);

        ChannelReply.RenderedImages second = reply.renderImageNodes(image -> "media_longer_second", BLOCK);
        assertEquals("![one](media_longer_second)", second.getText());
        assertEquals("![one](img_first)", first.getText());
        assertRenderedRange(first, 0, 0, "![one](img_first)", "（图片不可用：one；上传失败或渠道不支持）");
        assertSame(range, ranges.get(0));
        for (String text : Arrays.asList(null, "", "plain text")) {
            ChannelReply.RenderedImages empty = reply(text).renderImageNodes(noPublisher(), BLOCK);
            assertEquals(text, empty.getText());
            assertTrue(empty.getRanges().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> empty.getRanges().add(range));
        }
        ChannelReply.RenderedImages failed = reply.renderImageNodes(null, BLOCK);
        assertTrue(failed.getRanges().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> failed.getRanges().add(range));
    }

    private static void assertRenderedRange(ChannelReply.RenderedImages rendered, int index, int start,
                                            String node, String fallback) {
        ChannelReply.ImageRange range = rendered.getRanges().get(index);
        assertEquals(start, range.getStart());
        assertEquals(start + node.length(), range.getEnd());
        assertEquals(node, rendered.getText().substring(range.getStart(), range.getEnd()));
        assertEquals(fallback, range.getFallback());
    }

    private ChannelReply reply(String markdown) throws Exception {
        return ChannelReply.fromMarkdown(markdown, true, "run", temp.toRealPath().toString(), "");
    }

    private Path writePng(String name) throws Exception {
        Path file = temp.toRealPath().resolve(name);
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile()));
        return file;
    }

    private static Function<PublishedImage, String> noPublisher() {
        return image -> {
            fail("不应调用 publisher");
            return null;
        };
    }

    private static class TextChannel implements Channel {
        int calls;
        String text;
        boolean isFinal;
        List<String> route;

        @Override
        public String getChannelName() { return "text-only"; }

        @Override
        public boolean isBound(String sessionId) { return true; }

        @Override
        public void sendReply(String sessionId, String text, boolean isFinal) {
            fail("应调用携带路由的旧文本入口");
        }

        @Override
        public void sendReply(String sessionId, String text, boolean isFinal,
                              String sourceUserId, String replyTarget, String messageId) {
            calls++;
            this.text = text;
            this.isFinal = isFinal;
            this.route = Arrays.asList(sessionId, sourceUserId, replyTarget, messageId);
        }
    }
}
