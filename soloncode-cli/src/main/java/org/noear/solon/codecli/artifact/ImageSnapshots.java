package org.noear.solon.codecli.artifact;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;

/** 单回复持有的图片快照；相同规范相对路径共享首次加载结果（包括失败）。 */
public final class ImageSnapshots {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EDGE = 12000;
    // 解码前最多 1600 万像素，全进程最多两个准备任务。
    private static final long MAX_PIXELS = 16_000_000L;
    private static final long MAX_REPLY_BYTES = 25L * 1024 * 1024;
    private static final int MAX_PATH_CHARS = 16 * 1024;
    private static final int MAX_LOCAL_ATTEMPTS = 20;
    private static final Semaphore PREPARATIONS = new Semaphore(2);
    private final String workspaceRoot;
    private final String cwd;
    private final Map<String, FutureTask<PublishedImage>> snapshots = new HashMap<>();
    private int imageCount;
    private long totalBytes;
    private int pendingCount;
    private long pendingBytes;
    private final ImageLoadException attemptLimit = new ImageLoadException(
            ImageLoadException.Reason.LIMIT, "单回复最多尝试 20 个不同本地图片路径");

    public ImageSnapshots(String workspaceRoot, String cwd) {
        this.workspaceRoot = workspaceRoot;
        this.cwd = cwd;
    }

    /** 首次加载的说明随快照保存；重复引用不重读、不重复扣减预算。 */
    public PublishedImage load(String path, String alt) throws IOException {
        if (alt != null && alt.length() > 1000) {
            throw new IOException("图片说明最多 1000 字符");
        }
        String relative = normalize(path);
        FutureTask<PublishedImage> task;
        synchronized (this) {
            task = snapshots.get(relative);
            if (task == null) {
                if (snapshots.size() >= MAX_LOCAL_ATTEMPTS) {
                    throw attemptLimit;
                }
                task = new FutureTask<>(() -> loadImage(relative, alt));
                snapshots.put(relative, task);
            }
        }
        // 慢操作不持有快照锁；FutureTask 保证同一路径只执行一次。
        task.run();
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待图片快照时被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IOException("图片加载失败", cause);
        }
    }

    private PublishedImage loadImage(String path, String alt) throws IOException {
        readingLimit();
        // 满载直接拒绝，不建立全局等待队列。
        if (!PREPARATIONS.tryAcquire()) {
            throw new IOException("图片处理繁忙，请稍后重试");
        }
        int reserved = -1;
        boolean success = false;
        try {
            ResolvedImage file = resolve(path);
            int size = SecureImageReader.size(file.root, file.rootKey, file.target, readingLimit());
            beginPreparation(size);
            reserved = size;
            byte[] bytes = SecureImageReader.read(file.root, file.rootKey, file.target, size);
            if (bytes.length != size) {
                throw new IOException("图片预检后大小已改变");
            }
            String format = validate(bytes);
            String filename = Paths.get(path).getFileName().toString();
            String lower = filename.toLowerCase(java.util.Locale.ROOT);
            // 文件扩展名必须反映真实格式，便于下游按文件名上传。
            if (!(format.equals("png") ? lower.endsWith(".png")
                    : lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                filename += format.equals("png") ? ".png" : ".jpg";
            }
            PublishedImage image = new PublishedImage(bytes, filename, alt);
            success = true;
            return image;
        } finally {
            if (reserved >= 0) {
                finishPreparation(reserved, success);
            }
            PREPARATIONS.release();
        }
    }

    private synchronized void checkBudget() throws IOException {
        if (imageCount + pendingCount >= 5) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "单回复最多加载 5 张不同图片");
        }
    }

    private synchronized int readingLimit() throws IOException {
        checkBudget();
        long remaining = MAX_REPLY_BYTES - totalBytes - pendingBytes;
        if (remaining <= 0) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "单回复图片压缩字节总量最多 25MiB");
        }
        return (int) Math.min(MAX_BYTES, remaining);
    }

    /** 属性预检后再原子复核并预留，防止不同路径并发越过剩余预算。 */
    synchronized void beginPreparation(int size) throws IOException {
        checkBudget();
        if (size < 0 || size > MAX_BYTES || size > MAX_REPLY_BYTES - totalBytes - pendingBytes) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "图片超过剩余读取预算");
        }
        pendingCount++;
        pendingBytes += size;
    }

    synchronized void finishPreparation(int size, boolean success) {
        pendingCount--;
        pendingBytes -= size;
        if (success) {
            imageCount++;
            totalBytes += size;
        }
    }

    /** 仅用于成功快照的原子预算扣减；包可见以测试精确字节边界。 */
    synchronized void reserveBudget(int byteSize) throws IOException {
        checkBudget();
        if (byteSize < 0 || byteSize > MAX_REPLY_BYTES - totalBytes - pendingBytes) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "单回复图片压缩字节总量最多 25MiB");
        }
        imageCount++;
        totalBytes += byteSize;
    }

    private static String normalize(String value) throws IOException {
        if (value != null && value.length() > MAX_PATH_CHARS) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "图片目标最多 16384 字符");
        }
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("图片路径不能为空");
        }
        // 先按原始 URI 分类；原始 fragment 不是文件名，编码的 %23 则是。
        int fragment = value.indexOf('#');
        String raw = (fragment < 0 ? value : value.substring(0, fragment)).replace('\\', '/');
        try {
            // 保留既有空格/反斜杠路径支持；只为 URI 分类转义空格，不预先解码。
            URI uri = new URI(raw.replace(" ", "%20"));
            if (uri.getScheme() != null || uri.getRawAuthority() != null || raw.startsWith("//")) {
                throw new IOException("仅允许本轮 CWD 下的相对图片路径");
            }
        } catch (URISyntaxException e) {
            throw new IOException("图片 URI 无效", e);
        }
        String portable = decodePath(raw);
        if (portable.trim().isEmpty()) {
            throw new IOException("图片路径不能为空");
        }
        if (portable.startsWith("/") || portable.contains(":") || portable.startsWith("~")) {
            throw new IOException("仅允许本轮 CWD 下的相对图片路径");
        }
        for (String segment : portable.split("/")) {
            if (segment.equals("..") || segment.startsWith("@")) {
                throw new IOException("图片路径禁止 .. 和挂载别名");
            }
        }
        try {
            Path relative = Paths.get(portable);
            if (relative.isAbsolute()) {
                throw new IOException("不允许绝对路径");
            }
            return relative.normalize().toString();
        } catch (InvalidPathException e) {
            throw new IOException("图片路径无效", e);
        }
    }

    /** 严格解码一次；+ 不具有表单空格语义，编码分隔符不允许改变路径边界。 */
    private static String decodePath(String raw) throws IOException {
        StringBuilder decoded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') {
                decoded.append(raw.charAt(i++));
                continue;
            }
            // 只扫描当前连续编码段，避免多个短段按整个剩余路径反复分配。
            int end = i;
            while (end + 2 < raw.length() && raw.charAt(end) == '%') {
                end += 3;
            }
            byte[] bytes = new byte[(end - i) / 3];
            int count = 0;
            while (i < raw.length() && raw.charAt(i) == '%') {
                if (i + 2 >= raw.length()) {
                    throw new IOException("图片路径 percent 编码无效");
                }
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IOException("图片路径 percent 编码无效");
                }
                int value = (high << 4) | low;
                if (value == '/' || value == '\\') {
                    throw new IOException("图片路径禁止编码分隔符");
                }
                bytes[count++] = (byte) value;
                i += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, count)));
            } catch (CharacterCodingException e) {
                throw new IOException("图片路径 UTF-8 编码无效", e);
            }
        }
        return decoded.toString();
    }

    private ResolvedImage resolve(String value) throws IOException {
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
            Path target = base.resolve(Paths.get(value)).normalize();
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
                throw new ImageLoadException(ImageLoadException.Reason.FORMAT, "图片无法解码");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) {
                    throw new ImageLoadException(ImageLoadException.Reason.FORMAT, "仅支持 PNG/JPEG 图片");
                }
                // 忽略非像素元数据，交由标准解码器处理合法 PNG/JPEG 变体。
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE) {
                    throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "图片边长必须在 1 到 12000 之间");
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "图片总像素最多 1600 万");
                }
                // 通过尺寸预算后仅解码一次；不额外解析容器或重复解压 IDAT。
                BufferedImage decoded = reader.read(0);
                try {
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                        throw new ImageLoadException(ImageLoadException.Reason.FORMAT, "图片未能完整解码");
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
        } catch (ImageLoadException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImageLoadException(ImageLoadException.Reason.FORMAT, "图片数据无效");
        }
    }
}
