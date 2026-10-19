package org.noear.solon.codecli.channel;

import org.noear.solon.codecli.artifact.PublishedImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 渠道回复快照。图片已由发布层提供，不在渠道中读取文件。
 */
public final class ChannelReply {
    private final String text;
    private final boolean isFinal;
    private final String runId;
    private final List<PublishedImage> images;

    public ChannelReply(String text, boolean isFinal, String runId, List<PublishedImage> images) {
        this.text = text;
        this.isFinal = isFinal;
        this.runId = runId;
        List<PublishedImage> snapshot = new ArrayList<>();
        if (images != null) {
            for (PublishedImage image : images) {
                snapshot.add(Objects.requireNonNull(image, "image"));
            }
        }
        this.images = Collections.unmodifiableList(snapshot);
    }

    public String getText() {
        return text;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public String getRunId() {
        return runId;
    }

    public List<PublishedImage> getImages() {
        return images;
    }
}
