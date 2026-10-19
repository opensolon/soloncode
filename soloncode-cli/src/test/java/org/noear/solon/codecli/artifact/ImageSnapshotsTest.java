package org.noear.solon.codecli.artifact;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ImageSnapshotsTest {
    @TempDir
    Path temp;

    @org.junit.jupiter.api.BeforeEach
    void realTemporaryRoot() throws IOException {
        temp = temp.toRealPath();
    }

    @Test
    void targetLengthIsCheckedBeforeFragmentRemovalAndNormalization() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage first = snapshots.load("a.png", null);
        char[] fragment = new char[16384 - "a.png#".length()];
        Arrays.fill(fragment, 'x');
        String boundary = "a.png#" + new String(fragment);
        assertSame(first, snapshots.load(boundary, null));
        ImageLoadException failure = assertThrows(ImageLoadException.class,
                () -> snapshots.load(boundary + "x", null));
        assertEquals(ImageLoadException.Reason.LIMIT, failure.getReason());
    }

    @Test
    void targetLengthIsCheckedBeforeCollapsingDotSegments() throws Exception {
        image(temp.resolve("aa.png"), "png", 1, 1);
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < (16384 - "aa.png".length()) / 2; i++) {
            target.append("./");
        }
        target.append("aa.png");
        assertEquals(16384, target.length());
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage first = snapshots.load(target.toString(), null);
        assertSame(first, snapshots.load("aa.png", null));
        ImageLoadException failure = assertThrows(ImageLoadException.class,
                () -> snapshots.load("./" + target, null));
        assertEquals(ImageLoadException.Reason.LIMIT, failure.getReason());
    }

    @Test
    void manyShortPercentRunsNormalizeToSameSnapshot() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        StringBuilder target = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            target.append("%2e/");
        }
        target.append("%61.png");
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage first = snapshots.load(target.toString(), null);
        assertSame(first, snapshots.load("a.png", null));
    }

    @Test
    void failedAttemptsAreBoundedAndOldFailuresRemainSharedAfterLimit() throws Exception {
        ImageSnapshots snapshots = snapshots(temp, temp);
        IOException first = assertThrows(IOException.class, () -> snapshots.load("missing-0.png", null));
        for (int i = 1; i < 20; i++) {
            final String path = "missing-" + i + ".png";
            assertThrows(IOException.class, () -> snapshots.load(path, null));
        }
        image(temp.resolve("missing-0.png"), "png", 1, 1);
        assertSame(first, assertThrows(IOException.class, () -> snapshots.load("./missing-0.png#p", null)));
        ImageLoadException limit = assertThrows(ImageLoadException.class,
                () -> snapshots.load("new.png", null));
        assertEquals(ImageLoadException.Reason.LIMIT, limit.getReason());
        for (int i = 0; i < 100; i++) {
            final String path = "rejected-" + i + ".png";
            assertSame(limit, assertThrows(ImageLoadException.class, () -> snapshots.load(path, null)));
        }
        java.lang.reflect.Field field = ImageSnapshots.class.getDeclaredField("snapshots");
        field.setAccessible(true);
        assertEquals(20, ((java.util.Map<?, ?>) field.get(snapshots)).size());
    }

    @Test
    void successfulAndFailedPathsShareAttemptLimitButKeepBothCachedResults() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage first = snapshots.load("a.png", null);
        IOException missing = null;
        for (int i = 0; i < 19; i++) {
            final String path = "missing-" + i + ".png";
            IOException failure = assertThrows(IOException.class, () -> snapshots.load(path, null));
            assertFalse(failure instanceof ImageLoadException, "未满 20 个路径前必须实际尝试读取");
            if (i == 0) {
                missing = failure;
            }
        }
        assertSame(first, snapshots.load("./%61.png#preview", null));
        image(temp.resolve("missing-0.png"), "png", 1, 1);
        assertSame(missing, assertThrows(IOException.class,
                () -> snapshots.load("./missing-0.png", null)));
        image(temp.resolve("new.png"), "png", 1, 1);
        ImageLoadException limit = assertThrows(ImageLoadException.class,
                () -> snapshots.load("new.png", null));
        assertEquals(ImageLoadException.Reason.LIMIT, limit.getReason());
        assertNotNull(snapshots(temp, temp).load("new.png", null), "新一轮具有独立预算");
    }

    @Test
    void remainingBytesAreCheckedBeforeDecodeAndFullBudgetBeforeResolve() throws Exception {
        ImageSnapshots snapshots = snapshots(temp, temp);
        int mib = 1024 * 1024;
        snapshots.reserveBudget(10 * mib);
        snapshots.reserveBudget(10 * mib);
        Path corrupt = temp.resolve("too-large.png");
        try (RandomAccessFile file = new RandomAccessFile(corrupt.toFile(), "rw")) {
            file.setLength(5 * mib + 1);
        }
        ImageLoadException sizeFailure = assertThrows(ImageLoadException.class,
                () -> snapshots.load("too-large.png", null));
        assertEquals(ImageLoadException.Reason.LIMIT, sizeFailure.getReason());
        Path valid = image(temp.resolve("valid.png"), "png", 1, 1);
        snapshots.reserveBudget(5 * mib - (int) Files.size(valid));
        assertNotNull(snapshots.load("valid.png", null));
        ImageLoadException full = assertThrows(ImageLoadException.class,
                () -> snapshots.load("not-even-present.png", null));
        assertEquals(ImageLoadException.Reason.LIMIT, full.getReason());
    }

    @Test
    void pendingReservationsCountTowardBothLimitsAndFailuresReleaseThem() throws Exception {
        ImageSnapshots snapshots = snapshots(temp, temp);
        int mib = 1024 * 1024;
        snapshots.reserveBudget(10 * mib);
        snapshots.reserveBudget(10 * mib);
        snapshots.beginPreparation(5 * mib);
        assertThrows(ImageLoadException.class, () -> snapshots.beginPreparation(1));
        assertThrows(ImageLoadException.class, () -> snapshots.reserveBudget(1));
        snapshots.finishPreparation(5 * mib, false);
        snapshots.beginPreparation(5 * mib);
        snapshots.finishPreparation(5 * mib, true);
        snapshots.beginPreparation(0);
        snapshots.beginPreparation(0);
        assertThrows(ImageLoadException.class, () -> snapshots.beginPreparation(0));
        snapshots.finishPreparation(0, false);
        snapshots.beginPreparation(0);
        snapshots.finishPreparation(0, true);
        snapshots.finishPreparation(0, true);
        assertThrows(ImageLoadException.class, () -> snapshots.reserveBudget(0));
    }

    @Test
    void concurrentReservationsCannotOverbookLastBytesOrLastImageSlot() throws Exception {
        for (boolean byteLimit : new boolean[]{true, false}) {
            ImageSnapshots snapshots = snapshots(temp, temp);
            int size = byteLimit ? 5 * 1024 * 1024 : 1;
            if (byteLimit) {
                snapshots.reserveBudget(10 * 1024 * 1024);
                snapshots.reserveBudget(10 * 1024 * 1024);
            } else {
                for (int i = 0; i < 4; i++) {
                    snapshots.reserveBudget(1);
                }
            }
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> reserve = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("等待并发预留超时");
                }
                try {
                    snapshots.beginPreparation(size);
                    return true;
                } catch (ImageLoadException e) {
                    assertEquals(ImageLoadException.Reason.LIMIT, e.getReason());
                    return false;
                }
            };
            try {
                Future<Boolean> first = executor.submit(reserve);
                Future<Boolean> second = executor.submit(reserve);
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                snapshots.finishPreparation(size, false);
                snapshots.beginPreparation(size);
                snapshots.finishPreparation(size, true);
                assertThrows(ImageLoadException.class, () -> snapshots.beginPreparation(1));
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void formatFailureReleasesPendingBytesAndCountButKeepsFailureSnapshot() throws Exception {
        Files.write(temp.resolve("broken.png"), new byte[]{1, 2, 3});
        ImageSnapshots snapshots = snapshots(temp, temp);
        IOException broken = assertThrows(IOException.class, () -> snapshots.load("broken.png", null));
        for (int i = 0; i < 5; i++) {
            String path = "valid-" + i + ".png";
            image(temp.resolve(path), "png", 1, 1);
            assertNotNull(snapshots.load(path, null));
        }
        assertSame(broken, assertThrows(IOException.class, () -> snapshots.load("./broken.png", null)));
    }

    @Test
    void globalPreparationGateIsBoundedAndFailureReleasesPermit() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        java.lang.reflect.Field field = ImageSnapshots.class.getDeclaredField("PREPARATIONS");
        field.setAccessible(true);
        Semaphore gate = (Semaphore) field.get(null);
        assertEquals(2, gate.availablePermits());
        gate.acquire(2);
        try {
            assertThrows(IOException.class, () -> snapshots(temp, temp).load("a.png", null));
            assertThrows(IOException.class, () -> snapshots(temp, temp).load("a.png", null));
        } finally {
            gate.release(2);
        }
        assertThrows(IOException.class, () -> snapshots(temp, temp).load("missing.png", null));
        Files.write(temp.resolve("fake.png"), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> snapshots(temp, temp).load("fake.png", null));
        assertEquals(2, gate.availablePermits());
        assertNotNull(snapshots(temp, temp).load("a.png", null));
        assertEquals(2, gate.availablePermits());
    }

    @Test
    void acceptsLegalCompressedPngMetadataAndPreservesSnapshot() throws Exception {
        for (String type : Arrays.asList("zTXt", "iTXt", "iCCP")) {
            Path file = temp.resolve(type + ".png");
            writePngWithMetadata(file, type);
            byte[] original = Files.readAllBytes(file);
            PublishedImage published = snapshots(temp, temp).load(file.getFileName().toString(), null);
            assertArrayEquals(original, published.getBytes(), type);
        }
    }

    private void writePngWithMetadata(Path file, String type) throws Exception {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (javax.imageio.stream.ImageOutputStream output = ImageIO.createImageOutputStream(file.toFile())) {
            javax.imageio.metadata.IIOMetadata metadata = writer.getDefaultImageMetadata(
                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(pixels), writer.getDefaultWriteParam());
            javax.imageio.metadata.IIOMetadataNode root = new javax.imageio.metadata.IIOMetadataNode("javax_imageio_png_1.0");
            javax.imageio.metadata.IIOMetadataNode node = new javax.imageio.metadata.IIOMetadataNode(type);
            root.appendChild(node);
            if (type.equals("iCCP")) {
                node.setAttribute("profileName", "sRGB");
                node.setAttribute("compressionMethod", "deflate");
                java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
                try (java.util.zip.DeflaterOutputStream deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
                    deflater.write(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
                }
                node.setUserObject(compressed.toByteArray());
            } else {
                javax.imageio.metadata.IIOMetadataNode entry = new javax.imageio.metadata.IIOMetadataNode(type + "Entry");
                entry.setAttribute("keyword", "Description");
                if (type.equals("iTXt")) {
                    entry.setAttribute("compressionFlag", "TRUE");
                    entry.setAttribute("compressionMethod", "0");
                    entry.setAttribute("languageTag", "zh");
                    entry.setAttribute("translatedKeyword", "说明");
                    entry.setAttribute("text", "合法图片元数据");
                } else {
                    entry.setAttribute("compressionMethod", "deflate");
                    entry.setAttribute("text", "legal image metadata");
                }
                node.appendChild(entry);
            }
            metadata.mergeTree("javax_imageio_png_1.0", root);
            writer.setOutput(output);
            writer.write(null, new javax.imageio.IIOImage(pixels, null, metadata), writer.getDefaultWriteParam());
        } finally {
            writer.dispose();
            pixels.flush();
        }
        // 独立开启元数据读取，确认 fixture 确实写出了目标块，而不是仅写出普通 PNG。
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            javax.imageio.ImageReader reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input, false, false);
                org.w3c.dom.Node root = reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0");
                boolean found = false;
                for (org.w3c.dom.Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (type.equals(node.getNodeName())) found = true;
                }
                assertTrue(found, type);
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    void filenameReflectsRealFormatRatherThanInputExtension() throws Exception {
        image(temp.resolve("wrong.jpg"), "png", 1, 1);
        image(temp.resolve("wrong.png"), "jpeg", 1, 1);
        assertEquals("wrong.jpg.png", snapshots(temp, temp).load("wrong.jpg", null).getFilename());
        assertEquals("wrong.png.jpg", snapshots(temp, temp).load("wrong.png", null).getFilename());
    }

    private Path image(Path file, String format, int width, int height) throws IOException {
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try {
            assertTrue(ImageIO.write(pixels, format, file.toFile()));
        } finally {
            pixels.flush();
        }
        return file;
    }

    private ImageSnapshots snapshots(Path root, Path cwd) {
        return new ImageSnapshots(root.toString(), cwd.toString());
    }

    private void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前环境无法创建符号链接: " + e);
        }
    }

    @Test
    void relativeToCwdAndNormalizedSuccessCacheSurvivesDeletionAndReplacement() throws Exception {
        Path cwd = Files.createDirectories(temp.resolve("cwd/sub"));
        Path file = image(cwd.resolve("picture.png"), "png", 3, 2);
        byte[] original = Files.readAllBytes(file);
        ImageSnapshots snapshots = snapshots(temp, cwd.getParent());
        PublishedImage published = snapshots.load("sub\\.\\picture.png", "首次说明");
        Files.delete(file);
        assertSame(published, snapshots.load("sub/picture.png", "后续说明"));
        image(file, "jpeg", 1, 1);
        assertSame(published, snapshots.load("sub//./picture.png", null));
        assertArrayEquals(original, published.getBytes());
        assertEquals("首次说明", published.getCaption());
    }

    @Test
    void normalizedFailureCacheKeepsSameExceptionAfterFileIsRepaired() throws Exception {
        Path sub = Files.createDirectory(temp.resolve("sub"));
        ImageSnapshots snapshots = snapshots(temp, temp);
        IOException missing = assertThrows(IOException.class, () -> snapshots.load("sub\\.\\missing.png", null));
        image(sub.resolve("missing.png"), "png", 1, 1);
        assertSame(missing, assertThrows(IOException.class, () -> snapshots.load("sub/missing.png", "说明")));
        assertSame(missing, assertThrows(IOException.class, () -> snapshots.load("sub//./missing.png", null)));

        Path corrupt = Files.write(sub.resolve("corrupt.png"), new byte[]{1, 2, 3});
        IOException invalid = assertThrows(IOException.class, () -> snapshots.load("sub/corrupt.png", null));
        image(corrupt, "png", 1, 1);
        assertSame(invalid, assertThrows(IOException.class, () -> snapshots.load("sub\\corrupt.png", null)));
        // 缓存只属于当前实例，新实例仍可读取修复后的图片。
        assertNotNull(snapshots(temp, temp).load("sub/corrupt.png", null));
    }

    @Test
    void rejectsTraversalAbsoluteUrlsAndMountsBeforeCacheLookup() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage cached = snapshots.load("a.png", null);
        for (String path : Arrays.asList("../a.png", "sub/../a.png", "..\\a.png", "sub\\..\\a.png",
                "@mount/a.png", "sub/@mount/a.png", "https://host/a.png", "file:a.png", "C:\\a.png",
                "//host/a.png", temp.resolve("a.png").toString(), "", "a\u0000.png")) {
            assertThrows(IOException.class, () -> snapshots.load(path, null), path);
        }
        assertThrows(IOException.class, () -> snapshots.load(null, null));
        assertSame(cached, snapshots.load("./a.png", null));
    }

    @Test
    void decodesLocalUriOnceWithoutFormSemanticsAndSharesSnapshotAcrossFragments() throws Exception {
        String[][] paths = {
                {"my chart.png", "my%20chart.png"},
                {"中文😀.png", "%E4%B8%AD%E6%96%87%F0%9F%98%80.png"},
                {"a+b.png", "a%2Bb.png"},
                {"100%.png", "100%25.png"},
                {"chart#preview.png", "chart%23preview.png"}
        };
        for (String[] path : paths) {
            Path file = image(temp.resolve(path[0]), "png", 1, 1);
            ImageSnapshots snapshots = snapshots(temp, temp);
            PublishedImage first = snapshots.load(path[1], "首次");
            assertEquals(path[0], first.getFilename());
            assertArrayEquals(Files.readAllBytes(file), first.getBytes());
            Files.delete(file);
            assertSame(first, snapshots.load(path[1] + "#preview%GG", "后续"));
        }
        image(temp.resolve("a+b.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        PublishedImage plus = snapshots.load("a+b.png", null);
        assertSame(plus, snapshots.load("a%2bb.png#preview", null));
        image(temp.resolve("my chart.png"), "png", 1, 1);
        assertSame(snapshots.load("my chart.png", null), snapshots.load("my%20chart.png", null));
        image(temp.resolve("chart.png"), "png", 1, 1);
        assertSame(snapshots.load("chart.png#preview", null), snapshots.load("chart.png#other", null));
        IOException missing = assertThrows(IOException.class, () -> snapshots.load("missing%20chart.png#one", null));
        image(temp.resolve("missing chart.png"), "png", 1, 1);
        assertSame(missing, assertThrows(IOException.class, () -> snapshots.load("missing chart.png#two", null)));
    }

    @Test
    void doubleEncodingRemainsLiteralAndNeverRunsThroughASecondDecode() throws Exception {
        Path literalDirectory = Files.createDirectory(temp.resolve("%2e%2e"));
        image(literalDirectory.resolve("a.png"), "png", 1, 1);
        image(temp.resolve("%2f.png"), "png", 1, 1);
        image(temp.resolve("%5c.png"), "png", 1, 1);
        image(temp.resolve("my%20chart.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        assertEquals("a.png", snapshots.load("%252e%252e/a.png", null).getFilename());
        assertEquals("%2f.png", snapshots.load("%252f.png", null).getFilename());
        assertEquals("%5c.png", snapshots.load("%255c.png", null).getFilename());
        assertEquals("my%20chart.png", snapshots.load("my%2520chart.png", null).getFilename());
    }

    @Test
    void rejectsEncodedTraversalAndSeparatorsBeforeCacheLookup() throws Exception {
        Path cwd = Files.createDirectory(temp.resolve("cwd"));
        image(temp.resolve("a.png"), "png", 1, 1);
        image(cwd.resolve("a.png"), "png", 1, 1);
        Path sub = Files.createDirectory(cwd.resolve("sub"));
        image(sub.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, cwd);
        PublishedImage cached = snapshots.load("a.png", null);
        for (String path : Arrays.asList("%2e%2e/a.png", ".%2E/a.png", "%2e./a.png",
                "sub/%2E%2e/a.png", "%2e%2e%2fa.png", "sub%2fa.png", "sub%2Fa.png",
                "sub%5ca.png", "sub%5Ca.png", "%2fa.png", "%5ca.png")) {
            IOException failure = assertThrows(IOException.class, () -> snapshots.load(path, null), path);
            assertTrue(failure.getMessage().contains("禁止"), path + ": " + failure.getMessage());
        }
        assertSame(cached, snapshots.load("%61.png#preview", null));
    }

    @Test
    void rejectsMalformedPercentAndNonUtf8InsteadOfReadingReplacementNames() throws Exception {
        // 错误解码成替换字符也可能命中真实图片，不能仅以文件不存在判定失败。
        image(temp.resolve("�.png"), "png", 1, 1);
        for (String path : Arrays.asList("%.png", "%2.png", "%GG.png", "%FF.png", "%80.png",
                "%C0%AE.png", "%E4%B8.png", "%ED%A0%80.png", "%F4%90%80%80.png", "%00.png")) {
            assertThrows(IOException.class, () -> snapshots(temp, temp).load(path, null), path);
        }
    }

    @Test
    void encodedReservedPathsCannotBypassExistingLocalBoundary() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        for (String path : Arrays.asList("%40mount/a.png", "sub/%40mount/a.png", "%7Ea.png",
                "C%3Aa.png", "file%3Aa.png", "file:a.png", "data:image/png;base64,AA==",
                "//host/a.png", "https://host/a.png", "http://host/a.png", "#preview")) {
            assertThrows(IOException.class, () -> snapshots.load(path, null), path);
        }
    }

    @Test
    void rejectsInternalAndExternalFileDirectoryAndCwdSymlinks() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        Path internal = image(root.resolve("real.png"), "png", 1, 1);
        Path outside = image(temp.resolve("outside.png"), "png", 1, 1);
        Path sub = Files.createDirectory(root.resolve("sub"));
        image(sub.resolve("a.png"), "png", 1, 1);
        symlink(root.resolve("inside.png"), internal);
        symlink(root.resolve("outside.png"), outside);
        symlink(root.resolve("inside-dir"), sub);
        symlink(root.resolve("outside-dir"), temp);
        symlink(root.resolve("cwd-link"), sub);
        ImageSnapshots snapshots = snapshots(root, root);
        assertThrows(IOException.class, () -> snapshots.load("inside.png", null));
        assertThrows(IOException.class, () -> snapshots.load("outside.png", null));
        assertThrows(IOException.class, () -> snapshots.load("inside-dir/a.png", null));
        assertThrows(IOException.class, () -> snapshots.load("outside-dir/outside.png", null));
        assertThrows(IOException.class, () -> snapshots(root, temp).load("outside.png", null));
        assertThrows(IOException.class, () -> snapshots(root, root.resolve("outside-dir")).load("outside.png", null));
        assertThrows(IOException.class, () -> snapshots(root, root.resolve("cwd-link")).load("a.png", null));
    }

    private Object rootKey(Path root) throws IOException {
        return Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    @Test
    void secureReadRejectsResolvedTargetReplacedWithSymlink() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
        Path target = image(root.resolve("a.png"), "png", 1, 1).toRealPath();
        Path outside = image(temp.resolve("outside.png"), "png", 2, 2);
        Object key = rootKey(root);
        assertArrayEquals(Files.readAllBytes(target), ImageSnapshots.readSecure(root, key, target));
        // 保存 resolve 的真实路径后替换末级名称，而不是直接传入一个尚未解析的链接。
        Files.delete(target);
        symlink(target, outside);
        assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
    }

    @Test
    void secureReadRejectsResolvedParentReplacedWithSymlink() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
        Path parent = Files.createDirectory(root.resolve("sub"));
        Path target = image(parent.resolve("a.png"), "png", 1, 1).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("outside"));
        image(outside.resolve("a.png"), "png", 2, 2);
        Object key = rootKey(root);
        Files.move(parent, root.resolve("original-sub"));
        symlink(parent, outside);
        assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
    }

    @Test
    void secureReadRejectsRootReplacedWithSymlinkOrAnotherDirectory() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
        Path target = image(root.resolve("a.png"), "png", 1, 1).toRealPath();
        Object key = rootKey(root);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        image(outside.resolve("a.png"), "png", 2, 2);
        Files.move(root, root.resolveSibling("original-root"));
        symlink(root, outside);
        assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
        Files.delete(root);
        Files.createDirectory(root);
        image(root.resolve("a.png"), "png", 2, 2);
        assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
    }

    @Test
    void secureReadRejectsRootAncestorReplacedWithSymlink() throws Exception {
        Path ancestor = Files.createDirectory(temp.resolve("ancestor")).toRealPath();
        Path root = Files.createDirectory(ancestor.resolve("root"));
        Path target = image(root.resolve("a.png"), "png", 1, 1).toRealPath();
        Object key = rootKey(root);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path outsideRoot = Files.createDirectory(outside.resolve("root"));
        image(outsideRoot.resolve("a.png"), "png", 2, 2);
        Files.move(ancestor, ancestor.resolveSibling("original-ancestor"));
        symlink(ancestor, outside);
        assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
    }

    @Test
    void secureReadRejectsZipRootWithoutFileKey() throws Exception {
        URI archive = URI.create("jar:" + temp.resolve("images.zip").toUri());
        try (FileSystem zip = FileSystems.newFileSystem(archive, Collections.singletonMap("create", "true"))) {
            Path root = Files.createDirectory(zip.getPath("/root"));
            Path target = Files.write(root.resolve("a.png"), new byte[]{1, 2, 3});
            Object key = rootKey(root);
            assertNull(key);
            assertThrows(IOException.class, () -> ImageSnapshots.readSecure(root, key, target));
            assertThrows(IOException.class, () -> SecureImageReader.read(root, key, target, 3));
        }
    }

    @Test
    void sizePreflightAndReadRecheckGrowthAgainstReservedBudget() throws Exception {
        Path root = Files.createDirectory(temp.resolve("size-root")).toRealPath();
        Path target = Files.write(root.resolve("a.png"), new byte[]{1, 2, 3});
        Object key = rootKey(root);
        assertEquals(3, SecureImageReader.size(root, key, target, 3));
        ImageLoadException tooSmall = assertThrows(ImageLoadException.class,
                () -> SecureImageReader.size(root, key, target, 2));
        assertEquals(ImageLoadException.Reason.LIMIT, tooSmall.getReason());
        int reserved = SecureImageReader.size(root, key, target, 3);
        Files.write(target, new byte[]{1, 2, 3, 4});
        ImageLoadException grown = assertThrows(ImageLoadException.class,
                () -> SecureImageReader.read(root, key, target, reserved));
        assertEquals(ImageLoadException.Reason.LIMIT, grown.getReason());
        assertThrows(IOException.class, () -> SecureImageReader.size(root, key, target, -1));
        assertThrows(IOException.class, () -> SecureImageReader.size(root, null, target, 4));
        assertThrows(IOException.class, () -> SecureImageReader.size(root, key, root, 4));
    }

    @Test
    void nioReadsNestedAbsoluteTargetAndEnforcesExactByteBudget() throws Exception {
        Path root = Files.createDirectory(temp.resolve("nio-root")).toRealPath();
        Path file = image(Files.createDirectories(root.resolve("one/two")).resolve("a ' ; 中文.png"), "png", 2, 2);
        byte[] bytes = Files.readAllBytes(file);
        Object key = rootKey(root);
        assertNotNull(key);
        assertArrayEquals(bytes, SecureImageReader.read(root, key, file, bytes.length));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, file, bytes.length - 1));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, file, -1));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, null, file, bytes.length));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, new Object(), file, bytes.length));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, root.relativize(file), bytes.length));
        Path outside = image(temp.resolve("outside.png"), "png", 1, 1).toRealPath();
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, outside, bytes.length));
    }

    @Test
    void nioRejectsNonRegularFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("nio-root")).toRealPath();
        Path directory = Files.createDirectory(root.resolve("directory.png"));
        Object key = rootKey(root);
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, directory, 1024));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, root, 1024));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, root.resolve("missing.png"), 1024));
    }

    @Test
    void nioRejectsStableInternalFileAndDirectorySymlinks() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root")).toRealPath();
        Path nested = Files.createDirectories(root.resolve("one/two"));
        Path target = image(nested.resolve("a.png"), "png", 2, 2);
        Path directoryLink = root.resolve("dir-link");
        Path fileLink = nested.resolve("file-link.png");
        symlink(directoryLink, nested);
        symlink(fileLink, target);
        Object key = rootKey(root);
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, fileLink, 1024));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, directoryLink.resolve("a.png"), 1024));
        assertThrows(IOException.class, () -> SecureImageReader.read(root, key, directoryLink.resolve("file-link.png"), 1024));
    }

    @Test
    void enforcesCaptionLengthBeforeCacheLookupWithoutPollutingCacheOrBudget() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        char[] characters = new char[1000];
        Arrays.fill(characters, '说');
        String caption = new String(characters);
        assertThrows(IOException.class, () -> snapshots.load("a.png", caption + "明"));
        PublishedImage first = snapshots.load("./a.png", caption);
        assertEquals(caption, first.getCaption());
        assertThrows(IOException.class, () -> snapshots.load("a.png", caption + "明"));
        assertSame(first, snapshots.load("a.png", "后续说明"));
        IOException missing = assertThrows(IOException.class, () -> snapshots.load("missing.png", null));
        IOException invalidCaption = assertThrows(IOException.class, () -> snapshots.load("missing.png", caption + "明"));
        assertNotSame(missing, invalidCaption);
        assertSame(missing, assertThrows(IOException.class, () -> snapshots.load("./missing.png", null)));
        for (int i = 1; i < 5; i++) {
            String path = "caption-" + i + ".png";
            image(temp.resolve(path), "png", 1, 1);
            assertNotNull(snapshots.load(path, null));
        }
        image(temp.resolve("sixth.png"), "png", 1, 1);
        assertThrows(IOException.class, () -> snapshots.load("sixth.png", null));
    }

    @Test
    void reserveBudgetEnforcesExactByteLimitWithoutChargingFailures() throws Exception {
        ImageSnapshots snapshots = snapshots(temp, temp);
        int mib = 1024 * 1024;
        // 直接测试预算，不生成或解码大图；失败不能扣减字节数或成功次数。
        snapshots.reserveBudget(10 * mib);
        snapshots.reserveBudget(10 * mib);
        assertThrows(IOException.class, () -> snapshots.reserveBudget(5 * mib + 1));
        assertThrows(IOException.class, () -> snapshots.reserveBudget(5 * mib + 1));
        snapshots.reserveBudget(5 * mib);
        // 成功次数仍未到五次，此处必须因字节总量而拒绝。
        assertThrows(IOException.class, () -> snapshots.reserveBudget(1));
        snapshots.reserveBudget(0);
        snapshots.reserveBudget(0);
        assertThrows(IOException.class, () -> snapshots.reserveBudget(0));
    }

    @Test
    void reserveBudgetEnforcesFiveSuccessfulReservations() throws Exception {
        ImageSnapshots snapshots = snapshots(temp, temp);
        for (int i = 0; i < 5; i++) {
            snapshots.reserveBudget(1);
        }
        assertThrows(IOException.class, () -> snapshots.reserveBudget(1));
    }

    @Test
    void publishedImageDefensivelyCopiesInputAndReturnedBytes() {
        byte[] original = new byte[]{1, 2, 3};
        PublishedImage published = new PublishedImage(original, "a.png", "说明");
        original[0] = 9;
        byte[] returned = published.getBytes();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, published.getBytes());
        assertEquals("a.png", published.getFilename());
        assertEquals("说明", published.getCaption());
    }

    @Test
    void enforcesFiveDifferentSuccessfulPathsWithoutChargingFailuresOrCacheHits() throws Exception {
        Files.write(temp.resolve("fake.png"), new byte[]{1, 2, 3});
        Path huge = temp.resolve("huge.png");
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(10 * 1024 * 1024 + 1);
        }
        ImageSnapshots snapshots = snapshots(temp, temp);
        assertThrows(IOException.class, () -> snapshots.load("huge.png", null));
        IOException invalid = assertThrows(IOException.class, () -> snapshots.load("fake.png", null));
        assertSame(invalid, assertThrows(IOException.class, () -> snapshots.load("./fake.png", null)));
        assertThrows(IOException.class, () -> snapshots.load(".", null));
        List<PublishedImage> loaded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String path = "image-" + i + ".jpg";
            image(temp.resolve(path), "jpeg", 2, 2);
            PublishedImage published = snapshots.load(path, null);
            loaded.add(published);
            for (int repeat = 0; repeat < 6; repeat++) {
                assertSame(published, snapshots.load("./" + path, "后续说明"));
            }
        }
        Path sixth = image(temp.resolve("sixth.jpg"), "jpeg", 2, 2);
        IOException limit = assertThrows(IOException.class, () -> snapshots.load("sixth.jpg", null));
        Files.delete(sixth);
        assertSame(limit, assertThrows(IOException.class, () -> snapshots.load("./sixth.jpg", null)));
        assertSame(loaded.get(0), snapshots.load("image-0.jpg", null));
    }

    @Test
    void validatesContentsAndRejectsMissingHeadersAndPixelData() throws Exception {
        for (String format : Arrays.asList("png", "jpeg")) {
            Path file = image(temp.resolve("source." + format), format, 2, 2);
            byte[] bytes = Files.readAllBytes(file);
            assertNotNull(snapshots(temp, temp).load(file.getFileName().toString(), null));
            Path broken = temp.resolve("broken.png");
            // 只承诺 ImageIO 可解码，不强制检查 CRC、IEND 或容器尾部每个字节。
            for (int length : new int[]{0, 4, 16, 33}) {
                Files.write(broken, Arrays.copyOf(bytes, length));
                assertThrows(IOException.class, () -> snapshots(temp, temp).load("broken.png", null),
                        format + " prefix=" + length);
            }
        }
        image(temp.resolve("fake.png"), "gif", 2, 2);
        assertThrows(IOException.class, () -> snapshots(temp, temp).load("fake.png", null));
    }

    @Test
    void rejectsOversizeHeaderBeforePixelDecode() throws Exception {
        Path file = image(temp.resolve("a.png"), "png", 2, 2);
        byte[] bytes = Files.readAllBytes(file);
        // IHDR width=12001，重算 CRC，避免仅因 CRC 错误被拒绝。
        bytes[16] = 0;
        bytes[17] = 0;
        bytes[18] = 46;
        bytes[19] = (byte) 225;
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            bytes[29 + i] = (byte) (value >>> (24 - i * 8));
        }
        Files.write(file, bytes);
        IOException error = assertThrows(IOException.class, () -> snapshots(temp, temp).load("a.png", null));
        assertTrue(error.getMessage().contains("12000"));
        image(file, "png", 12000, 1);
        assertNotNull(snapshots(temp, temp).load("a.png", null));
    }

    @Test
    void rejectsSixteenMillionPixelLimitBeforeAllocatingLargeImage() throws Exception {
        Path file = image(temp.resolve("pixels.png"), "png", 2, 2);
        byte[] bytes = Files.readAllBytes(file);
        // 仅伪造 IHDR，不创建 12000×12000 的像素数组；宽高各自合法但乘积超限。
        java.nio.ByteBuffer.wrap(bytes, 16, 8).putInt(12000).putInt(12000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        java.nio.ByteBuffer.wrap(bytes, 29, 4).putInt((int) crc.getValue());
        Files.write(file, bytes);
        IOException error = assertThrows(IOException.class, () -> snapshots(temp, temp).load("pixels.png", null));
        // 必须因像素预算拒绝，而不是后续 IDAT 解码失败。
        assertTrue(error.getMessage().contains("像素"), error.getMessage());
    }

    @Test
    void rejectsCorruptPngPixelStream() throws Exception {
        Path file = image(temp.resolve("corrupt.png"), "png", 2, 2);
        byte[] bytes = Files.readAllBytes(file);
        int offset = 8;
        while (offset < bytes.length) {
            int length = java.nio.ByteBuffer.wrap(bytes, offset, 4).getInt();
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals("IDAT")) {
                // 破坏 zlib 头，让标准像素解码器报错；不依赖容器 CRC 校验。
                bytes[offset + 8] = 0;
                bytes[offset + 9] = 0;
                break;
            }
            offset += length + 12;
        }
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> snapshots(temp, temp).load("corrupt.png", null));
    }

    @Test
    void concurrentLoadsOfNormalizedPathShareOneObjectAndOneBudgetSlot() throws Exception {
        Path sub = Files.createDirectory(temp.resolve("sub"));
        Path file = image(sub.resolve("a.png"), "png", 1, 1);
        ImageSnapshots snapshots = snapshots(temp, temp);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PublishedImage>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final String path = i % 2 == 0 ? "sub\\.\\a.png" : "sub//a.png";
                final String caption = "说明" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("等待并发加载超时");
                    }
                    return snapshots.load(path, caption);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            PublishedImage first = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<PublishedImage> future : futures) {
                assertSame(first, future.get(5, TimeUnit.SECONDS));
            }
            Files.delete(file);
            assertSame(first, snapshots.load("sub/a.png", null));
            for (int i = 1; i < 5; i++) {
                String path = "other-" + i + ".png";
                image(temp.resolve(path), "png", 1, 1);
                assertNotNull(snapshots.load(path, null));
            }
            image(temp.resolve("sixth.png"), "png", 1, 1);
            assertThrows(IOException.class, () -> snapshots.load("sixth.png", null));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
