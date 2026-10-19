package org.noear.solon.codecli.artifact;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.LinkOption;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;


/** 请求持有的单轮图片登记簿；终结后不可再登记或领取。 */
public final class ImagePublishTurn {

    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EDGE = 12000;
    // 本产品比渠道限额更严：解码前最多 1600 万像素，全进程最多两个准备任务。
    private static final long MAX_PIXELS = 16_000_000L;
    private static final long MAX_TURN_BYTES = 25L * 1024 * 1024;
    private static final Semaphore PREPARATIONS = new Semaphore(2);
    private long totalBytes;
    private final String workspaceRoot;
    private final String cwd;
    private final List<PublishedImage> images = new ArrayList<>();
    private String runId;
    private boolean finished;

    public ImagePublishTurn(String workspaceRoot, String cwd) {
        this.workspaceRoot = workspaceRoot;
        this.cwd = cwd;
    }

    public synchronized void bindRunId(String runId) {
        if (finished || runId == null || runId.trim().isEmpty()) {
            throw new IllegalStateException("图片登记轮次已结束或 runId 无效");
        }
        if (this.runId != null && !this.runId.equals(runId)) {
            throw new IllegalStateException("图片登记轮次已绑定其他 runId");
        }
        this.runId = runId;
    }

    public synchronized String getRunId() {
        return runId;
    }

    public synchronized List<PublishedImage> finish(String runId, boolean normal) {
        if (finished) {
            return Collections.emptyList();
        }
        finished = true;
        List<PublishedImage> result = normal && this.runId != null && this.runId.equals(runId)
                ? Collections.unmodifiableList(new ArrayList<>(images)) : Collections.emptyList();
        images.clear();
        return result;
    }

    public synchronized void discard() {
        finished = true;
        images.clear();
    }

    public PublishedImage publish(String path, String caption) throws IOException {
        return commit(prepare(path, caption));
    }

    private synchronized void checkOpen() throws IOException {
        if (finished) {
            throw new IOException("本轮图片登记已结束");
        }
        if (images.size() >= 5) {
            throw new IOException("每轮最多登记 5 张图片");
        }
    }

    /** 慢操作不得持有 session.attrs 或本登记簿锁；满载直接拒绝，不建立等待队列。 */
    PublishedImage prepare(String path, String caption) throws IOException {
        checkOpen();
        if (caption != null && caption.length() > 1000) {
            throw new IOException("图片说明最多 1000 字符");
        }
        if (!PREPARATIONS.tryAcquire()) {
            throw new IOException("图片处理繁忙，请稍后重试");
        }
        try {
            return prepareImage(path, caption);
        } finally {
            PREPARATIONS.release();
        }
    }

    private PublishedImage prepareImage(String path, String caption) throws IOException {
        ResolvedImage file = resolve(path);
        byte[] bytes = readSecure(file.root, file.rootKey, file.target);
        String format = validate(bytes);
        String filename = Paths.get(path.replace('\\', '/')).getFileName().toString();
        // 文件扩展名必须反映真实格式，便于下游按文件名上传。
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (!(format.equals("png") ? lower.endsWith(".png")
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            filename += format.equals("png") ? ".png" : ".jpg";
        }
        return new PublishedImage(bytes, filename, caption);
    }

    synchronized PublishedImage commit(PublishedImage image) throws IOException {
        checkOpen();
        if (image.byteSize() > MAX_TURN_BYTES - totalBytes) {
            throw new IOException("每轮图片压缩字节总量最多 25MiB");
        }
        images.add(image);
        totalBytes += image.byteSize();
        return image;
    }

    private ResolvedImage resolve(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("图片路径不能为空");
        }
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/") || portable.contains(":") || portable.startsWith("~")) {
            throw new IOException("仅允许本轮 CWD 下的相对图片路径");
        }
        for (String segment : portable.split("/")) {
            if (segment.equals("..") || segment.startsWith("@")) {
                throw new IOException("图片路径禁止 .. 和挂载别名");
            }
        }
        try {
            Path rootPath = Paths.get(workspaceRoot).toAbsolutePath().normalize();
            SecureImageReader.rejectLinks(rootPath);
            Path root = rootPath.toRealPath();
            BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory() || rootAttributes.fileKey() == null) {
                throw new IOException("无法验证工作区根目录身份");
            }

            Path base = cwd == null || cwd.isEmpty() ? root : Paths.get(cwd);
            if (!base.isAbsolute()) {
                base = root.resolve(base);
            }
            base = base.toAbsolutePath().normalize();
            SecureImageReader.rejectLinks(base);
            base = base.toRealPath();
            if (!Files.isDirectory(root) || !Files.isDirectory(base) || !base.startsWith(root)) {
                throw new IOException("本轮 CWD 必须在工作区内");
            }
            Path relative = Paths.get(portable);
            if (relative.isAbsolute()) {
                throw new IOException("不允许绝对路径");
            }
            Path target = base.resolve(relative).normalize();
            SecureImageReader.rejectLinks(target);
            Path real = target.toRealPath();
            if (!real.startsWith(root)) {
                throw new IOException("图片真实路径越出工作区");
            }
            return new ResolvedImage(root, rootAttributes.fileKey(), real);
        } catch (InvalidPathException | NullPointerException e) {
            throw new IOException("图片路径无效", e);
        }
    }

    private static final class ResolvedImage {
        final Path root;
        final Object rootKey;
        final Path target;
        ResolvedImage(Path root, Object rootKey, Path target) {
            this.root = root;
            this.rootKey = rootKey;
            this.target = target;

        }
    }

    /** 仅接受已解析的真实目标；包可见以确定性测试 resolve 与打开之间的替换。 */
    static byte[] readSecure(Path root, Object rootKey, Path target) throws IOException {
        return SecureImageReader.read(root, rootKey, target, MAX_BYTES);
    }

    private static String validate(byte[] bytes) throws IOException {
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("图片无法解码");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new IOException("仅支持 PNG/JPEG 图片");
                }
                // 忽略非像素元数据，交由标准解码器处理合法 PNG/JPEG 变体。
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE) {
                    throw new IOException("图片边长必须在 1 到 12000 之间");
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw new IOException("图片总像素最多 1600 万");
                }
                // 通过尺寸预算后仅解码一次；不额外解析容器或重复解压 IDAT。
                BufferedImage decoded = reader.read(0);
                try {
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                        throw new IOException("图片未能完整解码");
                    }
                } finally {
                    if (decoded != null) {
                        decoded.flush();
                    }
                }
                return format;
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("图片数据无效", e);
        }
    }

    
}
