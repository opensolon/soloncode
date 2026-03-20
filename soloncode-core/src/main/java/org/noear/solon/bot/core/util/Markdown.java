package org.noear.solon.bot.core.util;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;

/**
 *
 * @author noear 2026/3/20 created
 *
 */
public class Markdown {
    protected final ONode metadata = new ONode(Options.of(Feature.Decode_IgnoreError));
    protected String content = "";

    public ONode getMetadata() {
        return metadata;
    }

    public String getContent() {
        return content;
    }
}
