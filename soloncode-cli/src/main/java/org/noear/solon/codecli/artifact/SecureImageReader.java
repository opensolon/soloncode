package org.noear.solon.codecli.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Java 8 NIO 有界读取；工作区应可信，读取期间不应被不可信进程修改。
 * 路径前后检查并非原子操作，不能阻止同目录恶意进程的全部 TOCTOU 攻击，
 * 也不能通过路径属性证明已打开通道的 fileKey。Java 8 没有通用 O_NONBLOCK，
 * 因此不能保证普通文件在检查后被换成 FIFO 时，打开或读取不会阻塞。
 */
final class SecureImageReader {
    private SecureImageReader() {
    }

    /** 读取前验证普通文件及大小；实际读取仍独立复核全部路径属性。 */
    static int size(Path root, Object rootKey, Path target, int maxBytes) throws IOException {
        try {
            requireAbsoluteNormalized(root);
            requireAbsoluteNormalized(target);
            if (rootKey == null || maxBytes < 0 || target.equals(root) || !target.startsWith(root)) {
                throw new IOException("图片读取路径、根身份或预算无效");
            }
            return (int) inspect(root, rootKey, target, maxBytes).size();
        } catch (RuntimeException e) {
            throw new IOException("无法安全检查图片大小", e);
        }
    }
    static byte[] read(Path root, Object rootKey, Path target, int maxBytes) throws IOException {
        try {
            requireAbsoluteNormalized(root);
            requireAbsoluteNormalized(target);
            if (rootKey == null || maxBytes < 0 || target.equals(root) || !target.startsWith(root)) {
                throw new IOException("图片读取路径、根身份或预算无效");
            }

            BasicFileAttributes before = inspect(root, rootKey, target, maxBytes);
            try (SeekableByteChannel channel = Files.newByteChannel(target,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() != before.size()) {
                    throw new IOException("图片大小已改变");
                }
                // 打开后先复核大小，再为完整内容分配内存。
                byte[] bytes = new byte[(int) before.size()];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) <= 0) {
                        throw new IOException("图片读取不完整或无进展");
                    }
                }
                // 最多读取预检大小加一个探测字节，不使用无界读取或溢出的 maxBytes + 1。
                if (channel.read(ByteBuffer.allocate(1)) != -1 || channel.size() != bytes.length) {
                    throw new IOException("图片大小或读取字节数不匹配");
                }

                BasicFileAttributes after = inspect(root, rootKey, target, maxBytes);
                if (!before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                        || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || after.size() != bytes.length || channel.size() != bytes.length) {
                    throw new IOException("图片身份、大小或修改时间已改变");
                }
                return bytes;
            }
        } catch (RuntimeException e) {
            throw new IOException("无法安全读取图片", e);
        }
    }

    /** 供 root/cwd 调用方使用：从文件系统根开始，检查绝对路径的每一级。 */
    static void rejectLinks(Path path) throws IOException {
        try {
            requireAbsoluteNormalized(path);
            Path current = path.getRoot();
            BasicFileAttributes attributes = attributes(current);
            rejectLinkOrOther(attributes);
            for (Path part : path) {
                if (!attributes.isDirectory()) {
                    throw new IOException("路径祖先不是目录");
                }
                current = current.resolve(part);
                attributes = attributes(current);
                rejectLinkOrOther(attributes);
            }
        } catch (RuntimeException e) {
            throw new IOException("无法检查路径链接", e);
        }
    }

    private static BasicFileAttributes inspect(Path root, Object rootKey, Path target, int maxBytes)
            throws IOException {
        // 包括 root 以上的祖先，以及 target 本身；不能仅检查 root 内的相对路径。
        rejectLinks(target);
        BasicFileAttributes rootAttributes = attributes(root);
        if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink() || rootAttributes.isOther()
                || rootAttributes.fileKey() == null || !rootKey.equals(rootAttributes.fileKey())) {
            throw new IOException("无法验证工作区根目录身份");
        }
        Path real = target.toRealPath();
        if (!real.equals(target) || real.equals(root) || !real.startsWith(root)) {
            throw new IOException("图片真实路径不匹配或越出工作区");
        }
        BasicFileAttributes attributes = attributes(target);
        if (attributes.size() > maxBytes) {
            throw new ImageLoadException(ImageLoadException.Reason.LIMIT, "图片超过读取预算");
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.fileKey() == null || attributes.size() < 0) {
            throw new IOException("图片不是可验证的普通文件或超过读取预算");
        }
        return attributes;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void rejectLinkOrOther(BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("路径包含链接或特殊文件");
        }
    }

    private static void requireAbsoluteNormalized(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IOException("路径必须为已规范化的绝对路径");
        }
    }
}
