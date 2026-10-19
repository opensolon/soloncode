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
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class ImagePublishTurnTest {
    @TempDir
    Path temp;

    @org.junit.jupiter.api.BeforeEach
    void realTemporaryRoot() throws IOException {
        temp = temp.toRealPath();
    }

    @Test
    void globalPreparationGateIsBoundedAndFailureReleasesPermit() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        java.lang.reflect.Field field = ImagePublishTurn.class.getDeclaredField("PREPARATIONS");
        field.setAccessible(true);
        Semaphore gate = (Semaphore) field.get(null);
        assertEquals(2, gate.availablePermits());
        gate.acquire(2);
        try {
            assertThrows(IOException.class, () -> turn(temp, temp).prepare("a.png", null));
            assertThrows(IOException.class, () -> turn(temp, temp).prepare("a.png", null));
        } finally {
            gate.release(2);
        }
        assertThrows(IOException.class, () -> turn(temp, temp).prepare("missing.png", null));
        assertEquals(2, gate.availablePermits());
        assertNotNull(turn(temp, temp).publish("a.png", null));
        assertEquals(2, gate.availablePermits());
    }

    @Test
    void acceptsLegalCompressedPngMetadataAndPreservesSnapshot() throws Exception {
        for (String type : Arrays.asList("zTXt", "iTXt", "iCCP")) {
            Path file = temp.resolve(type + ".png");
            writePngWithMetadata(file, type);
            byte[] original = Files.readAllBytes(file);
            PublishedImage published = turn(temp, temp).publish(file.getFileName().toString(), null);
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
        assertEquals("wrong.jpg.png", turn(temp, temp).publish("wrong.jpg", null).getFilename());
        assertEquals("wrong.png.jpg", turn(temp, temp).publish("wrong.png", null).getFilename());
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

    private ImagePublishTurn turn(Path root, Path cwd) {
        return new ImagePublishTurn(root.toString(), cwd.toString());
    }

    private void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "当前环境无法创建符号链接: " + e);
        }
    }

    @Test
    void relativeToTurnCwdAndSnapshotSurvivesDeletion() throws Exception {
        Path cwd = Files.createDirectory(temp.resolve("sub"));
        Path file = image(cwd.resolve("picture.png"), "png", 3, 2);
        byte[] original = Files.readAllBytes(file);
        ImagePublishTurn turn = turn(temp, cwd);
        turn.bindRunId("run");
        turn.bindRunId("run");
        assertEquals("run", turn.getRunId());
        PublishedImage published = turn.publish("picture.png", "说明");
        Files.delete(file);
        List<PublishedImage> result = turn.finish("run", true);
        assertEquals(1, result.size());
        assertSame(published, result.get(0));
        assertArrayEquals(original, published.getBytes());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertTrue(turn.finish("run", true).isEmpty());
        assertThrows(IOException.class, () -> turn.publish("picture.png", null));
        assertThrows(IllegalStateException.class, () -> turn.bindRunId("run"));
    }

    @Test
    void everyFinishIsTerminalAndOnlyBoundNormalRunCanClaim() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        for (int mode = 0; mode < 4; mode++) {
            ImagePublishTurn turn = turn(temp, temp);
            turn.publish("a.png", null);
            if (mode != 0) {
                turn.bindRunId("run");
                assertThrows(IllegalStateException.class, () -> turn.bindRunId("other"));
            }
            if (mode == 3) {
                turn.discard();
            }
            assertTrue(turn.finish(mode == 1 ? "other" : "run", mode != 2).isEmpty());
            assertTrue(turn.finish("run", true).isEmpty());
        }
    }

    @Test
    void rejectsTraversalAbsoluteUrlsAndMounts() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImagePublishTurn turn = turn(temp, temp);
        for (String path : Arrays.asList("../a.png", "sub/../a.png", "..\\a.png", "@mount/a.png",
                "sub/@mount/a.png", "https://host/a.png", "file:a.png", "C:\\a.png", "//host/a.png",
                temp.resolve("a.png").toString(), "", "a\u0000.png")) {
            assertThrows(IOException.class, () -> turn.publish(path, null), path);
        }
        assertThrows(IOException.class, () -> turn.publish(null, null));
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
        ImagePublishTurn turn = turn(root, root);
        assertThrows(IOException.class, () -> turn.publish("inside.png", null));
        assertThrows(IOException.class, () -> turn.publish("outside.png", null));
        assertThrows(IOException.class, () -> turn.publish("inside-dir/a.png", null));
        assertThrows(IOException.class, () -> turn.publish("outside-dir/outside.png", null));
        assertThrows(IOException.class, () -> turn(root, temp).publish("outside.png", null));
        assertThrows(IOException.class, () -> turn(root, root.resolve("outside-dir")).publish("outside.png", null));
        assertThrows(IOException.class, () -> turn(root, root.resolve("cwd-link")).publish("a.png", null));
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
        assertArrayEquals(Files.readAllBytes(target), ImagePublishTurn.readSecure(root, key, target));
        // 保存 resolve 的真实路径后替换末级名称，而不是直接传入一个尚未解析的链接。
        Files.delete(target);
        symlink(target, outside);
        assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
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
        assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
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
        assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
        Files.delete(root);
        Files.createDirectory(root);
        image(root.resolve("a.png"), "png", 2, 2);
        assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
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
        assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
    }

    @Test
    void secureReadRejectsZipRootWithoutFileKey() throws Exception {
        URI archive = URI.create("jar:" + temp.resolve("images.zip").toUri());
        try (FileSystem zip = FileSystems.newFileSystem(archive, Collections.singletonMap("create", "true"))) {
            Path root = Files.createDirectory(zip.getPath("/root"));
            Path target = Files.write(root.resolve("a.png"), new byte[]{1, 2, 3});
            Object key = rootKey(root);
            assertNull(key);
            assertThrows(IOException.class, () -> ImagePublishTurn.readSecure(root, key, target));
            assertThrows(IOException.class, () -> SecureImageReader.read(root, key, target, 3));
        }
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
    void enforcesCaptionLengthWithoutChargingRejectedCaption() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImagePublishTurn turn = turn(temp, temp);
        char[] characters = new char[1000];
        Arrays.fill(characters, '说');
        String caption = new String(characters);
        assertThrows(IOException.class, () -> turn.publish("a.png", caption + "明"));
        assertEquals(caption, turn.publish("a.png", caption).getCaption());
        for (int i = 1; i < 5; i++) {
            assertNotNull(turn.publish("a.png", null));
        }
        turn.bindRunId("run");
        assertEquals(5, turn.finish("run", true).size());
    }

    @Test
    void preparedImageCannotCommitAfterDiscard() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImagePublishTurn turn = turn(temp, temp);
        turn.bindRunId("run");
        PublishedImage prepared = turn.prepare("a.png", "说明");
        assertNotNull(prepared);
        turn.discard();
        assertThrows(IOException.class, () -> turn.commit(prepared));
        assertTrue(turn.finish("run", true).isEmpty());
        assertThrows(IOException.class, () -> turn.publish("a.png", null));
    }

    @Test
    void prepareDoesNotRegisterUntilCommit() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImagePublishTurn turn = turn(temp, temp);
        turn.bindRunId("run");
        PublishedImage prepared = turn.prepare("a.png", null);
        assertTrue(turn.finish("run", true).isEmpty());
        assertThrows(IOException.class, () -> turn.commit(prepared));

        ImagePublishTurn committed = turn(temp, temp);
        committed.bindRunId("run");
        PublishedImage candidate = committed.prepare("a.png", "说明");
        assertSame(candidate, committed.commit(candidate));
        assertEquals(Collections.singletonList(candidate), committed.finish("run", true));
    }

    @Test
    void commitEnforcesExactTurnByteBudgetWithoutChargingRejectedImage() throws Exception {
        ImagePublishTurn turn = turn(temp, temp);
        turn.bindRunId("run");
        int mib = 1024 * 1024;
        // 直接测试登记预算，不生成或解码大图；数量尚未触及五张上限。
        PublishedImage ten = new PublishedImage(new byte[10 * mib], "ten.png", null);
        assertSame(ten, turn.commit(ten));
        turn.commit(new PublishedImage(new byte[10 * mib], "another-ten.png", null));
        PublishedImage over = new PublishedImage(new byte[5 * mib + 1], "over.png", null);
        assertThrows(IOException.class, () -> turn.commit(over));
        PublishedImage five = new PublishedImage(new byte[5 * mib], "five.png", null);
        assertSame(five, turn.commit(five));
        PublishedImage extra = new PublishedImage(new byte[1], "extra.png", null);
        assertThrows(IOException.class, () -> turn.commit(extra));
        List<PublishedImage> result = turn.finish("run", true);
        assertEquals(3, result.size());
        assertSame(five, result.get(2));
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
    void enforcesCountAndActualSizeWithoutChargingFailedValidation() throws Exception {
        image(temp.resolve("a.jpg"), "jpeg", 2, 2);
        Files.write(temp.resolve("fake.png"), new byte[]{1, 2, 3});
        Path huge = temp.resolve("huge.png");
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(10 * 1024 * 1024 + 1);
        }
        ImagePublishTurn turn = turn(temp, temp);
        assertThrows(IOException.class, () -> turn.publish("huge.png", null));
        assertThrows(IOException.class, () -> turn.publish("fake.png", null));
        assertThrows(IOException.class, () -> turn.publish(".", null));
        for (int i = 0; i < 5; i++) {
            assertNotNull(turn.publish("a.jpg", null));
        }
        assertThrows(IOException.class, () -> turn.publish("a.jpg", null));
        turn.bindRunId("run");
        assertEquals(5, turn.finish("run", true).size());
    }

    @Test
    void validatesContentsAndRejectsMissingHeadersAndPixelData() throws Exception {
        for (String format : Arrays.asList("png", "jpeg")) {
            Path file = image(temp.resolve("source." + format), format, 2, 2);
            byte[] bytes = Files.readAllBytes(file);
            assertNotNull(turn(temp, temp).publish(file.getFileName().toString(), null));
            Path broken = temp.resolve("broken.png");
            // 只承诺 ImageIO 可解码，不强制检查 CRC、IEND 或容器尾部每个字节。
            for (int length : new int[]{0, 4, 16, 33}) {
                Files.write(broken, Arrays.copyOf(bytes, length));
                assertThrows(IOException.class, () -> turn(temp, temp).publish("broken.png", null),
                        format + " prefix=" + length);
            }
        }
        image(temp.resolve("fake.png"), "gif", 2, 2);
        assertThrows(IOException.class, () -> turn(temp, temp).publish("fake.png", null));
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
        IOException error = assertThrows(IOException.class, () -> turn(temp, temp).publish("a.png", null));
        assertTrue(error.getMessage().contains("12000"));
        image(file, "png", 12000, 1);
        assertNotNull(turn(temp, temp).publish("a.png", null));
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
        IOException error = assertThrows(IOException.class, () -> turn(temp, temp).publish("pixels.png", null));
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
        assertThrows(IOException.class, () -> turn(temp, temp).publish("corrupt.png", null));
    }

    @Test
    void concurrentFinishClaimsImagesOnlyOnce() throws Exception {
        image(temp.resolve("a.png"), "png", 1, 1);
        ImagePublishTurn turn = turn(temp, temp);
        turn.bindRunId("run");
        turn.publish("a.png", null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<PublishedImage>> first = executor.submit(() -> turn.finish("run", true));
            Future<List<PublishedImage>> second = executor.submit(() -> turn.finish("run", true));
            assertEquals(1, first.get(5, TimeUnit.SECONDS).size() + second.get(5, TimeUnit.SECONDS).size());
        } finally {
            executor.shutdownNow();
        }
    }
}
