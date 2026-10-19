package org.noear.solon.codecli.artifact;

import java.util.Objects;
import java.util.UUID;

/** 已校验图片的不可变内存快照，不再依赖源文件。 */
public final class PublishedImage {
    private final byte[] bytes;
    private final String filename;
    private final String caption;
    private final String uuid;

    public PublishedImage(byte[] bytes, String filename, String caption) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.filename = Objects.requireNonNull(filename, "filename");
        this.caption = caption;
        this.uuid = UUID.randomUUID().toString();
    }

    public byte[] getBytes() {
        return bytes.clone();
    }

    /** 不复制图片数据即可查询上传预算。 */
    public int byteSize() {
        return bytes.length;
    }

    public String getFilename() {
        return filename;
    }

    public String getCaption() {
        return caption;
    }

    public String getUuid() {
        return uuid;
    }
}
