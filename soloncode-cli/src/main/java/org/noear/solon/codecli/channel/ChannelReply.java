package org.noear.solon.codecli.channel;

import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.Reference;
import com.vladsch.flexmark.ast.LinkRef;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import org.noear.solon.codecli.artifact.ImageLoadException;
import org.noear.solon.codecli.artifact.ImageSnapshots;
import org.noear.solon.codecli.artifact.PublishedImage;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** 最终 Markdown 及其原位图片引用的不可变快照。 */
public final class ChannelReply {
    private static final Parser PARSER = Parser.builder().build();
    /** 外链仅在客户端展示，绝不由服务端下载；默认保持安全占位。 */
    public enum ExternalImagePolicy { BLOCK, HTTPS_IMAGE, HTTP_LINK }
    private final String text;
    private final boolean isFinal;
    private final String runId;
    private final List<ImageReference> references;
    private final List<PublishedImage> images;
    private final List<int[]> definitions;

    private ChannelReply(String text, boolean isFinal, String runId,
                         List<ImageReference> references, List<int[]> definitions) {
        this.text = text;
        this.isFinal = isFinal;
        this.runId = runId;
        this.references = Collections.unmodifiableList(new ArrayList<>(references));
        this.definitions = new ArrayList<>(definitions);
        LinkedHashSet<PublishedImage> unique = new LinkedHashSet<>();
        for (ImageReference reference : references) {
            if (reference.image != null) unique.add(reference.image);
        }
        this.images = Collections.unmodifiableList(new ArrayList<>(unique));
    }

    public static ChannelReply fromMarkdown(String text, boolean isFinal, String runId, String root, String cwd) {
        return fromMarkdown(text, isFinal, runId, root, cwd, true);
    }

    /** 禁图时仍识别引用并生成安全占位，但绝不访问图片文件。 */
    public static ChannelReply fromMarkdown(String text, boolean isFinal, String runId,
                                             String root, String cwd, boolean imagesAllowed) {
        List<ImageReference> references = new ArrayList<>();
        List<int[]> definitions = new ArrayList<>();
        if (isFinal && text != null && !text.isEmpty()) {
            Document document = PARSER.parse(text);
            // 普通链接是用户显式内容；共享定义必须保留，不能随图片替换一起删除。
            Set<Reference> linkDefinitions = new HashSet<>();
            for (Node node : document.getDescendants()) {
                if (node instanceof LinkRef) {
                    Reference definition = ((LinkRef) node).getReferenceNode(document);
                    if (definition != null) linkDefinitions.add(definition);
                }
            }
            ImageSnapshots snapshots = imagesAllowed ? new ImageSnapshots(root, cwd) : null;
            int previousEnd = -1;
            for (Node node : document.getDescendants()) {
                String alt;
                String path;
                if (node instanceof Image) {
                    Image image = (Image) node;
                    alt = image.getText().unescape();
                    path = image.getUrl().unescape();
                } else if (node instanceof ImageRef) {
                    ImageRef image = (ImageRef) node;
                    Reference definition = image.getReferenceNode(document);
                    if (definition == null) continue;
                    alt = (image.isReferenceTextCombined() ? image.getReference() : image.getText()).unescape();
                    path = definition.getUrl().unescape();
                    // 仅删除图片独占的定义；普通链接共享的定义属于用户显式内容。
                    if (!linkDefinitions.contains(definition)) {
                        definitions.add(new int[]{definition.getStartOffset(), definition.getEndOffset()});
                    }
                } else {
                    continue;
                }
                if (node.getStartOffset() < previousEnd) continue;
                alt = safeAlt(alt);
                PublishedImage image = null;
                String externalUrl = null;
                String reason = imagesAllowed ? "读取失败或目标不安全" : "图片发送已禁用";
                boolean external = path.regionMatches(true, 0, "http:", 0, 5)
                        || path.regionMatches(true, 0, "https:", 0, 6);
                if (imagesAllowed && external) {
                    externalUrl = safeExternalUrl(path);
                    reason = externalUrl == null ? "外链不安全" : "当前渠道未启用外链图片";
                } else if (snapshots != null) {
                    try {
                        image = snapshots.load(path, alt);
                        reason = "上传失败或渠道不支持";
                    } catch (ImageLoadException e) {
                        reason = e.getReason().getText();
                    } catch (IOException | RuntimeException ignored) {
                        // 绝不回显底层异常消息。
                    }
                }
                references.add(new ImageReference(node.getStartOffset(), node.getEndOffset(), alt,
                        image, externalUrl, reason));
                previousEnd = node.getEndOffset();
            }
        }
        return new ChannelReply(text, isFinal, runId, references, definitions);
    }

    public String getText() { return text; }
    public boolean isFinal() { return isFinal; }
    public String getRunId() { return runId; }
    public List<PublishedImage> getImages() { return images; }
    public List<ImageReference> getImageReferences() { return references; }
    public boolean hasImageReferences() { return !references.isEmpty(); }

    /** 撤销本地快照和外链许可，不重新读取文件。 */
    public ChannelReply withoutImages() {
        List<ImageReference> safe = new ArrayList<>();
        for (ImageReference reference : references) {
            safe.add(new ImageReference(reference.start, reference.end, reference.alt, null, null, "图片发送已禁用"));
        }
        return new ChannelReply(text, isFinal, runId, safe, definitions);
    }

    public String renderImages(Function<PublishedImage, String> publisher) {
        return renderImages(publisher, ExternalImagePolicy.BLOCK);
    }

    /** 外链策略不影响本地图片上传；单参数入口仍默认禁用外链。 */
    public String renderImages(Function<PublishedImage, String> publisher, ExternalImagePolicy externalPolicy) {
        return renderImageNodes(publisher, externalPolicy).getText();
    }

    /** 沿用已解析的真实图片节点，在渠道替换时记录输出范围，不重新解析正文。 */
    public RenderedImages renderImageNodes(Function<PublishedImage, String> publisher, ExternalImagePolicy externalPolicy) {
        if (references.isEmpty()) return new RenderedImages(text, Collections.emptyList());
        Map<PublishedImage, String> targets = new IdentityHashMap<>();
        SortedMap<Integer, Replacement> replacements = new TreeMap<>();
        for (int[] definition : definitions) {
            replacements.put(definition[0], new Replacement(definition[1], ""));
        }
        for (ImageReference reference : references) {
            String target = null;
            boolean link = false;
            if (reference.externalUrl != null) {
                if (externalPolicy == ExternalImagePolicy.HTTP_LINK) {
                    target = reference.externalUrl;
                    link = true;
                } else if (externalPolicy == ExternalImagePolicy.HTTPS_IMAGE
                        && reference.externalUrl.startsWith("https:")) {
                    target = reference.externalUrl;
                }
            } else if (reference.image != null && publisher != null) {
                if (!targets.containsKey(reference.image)) {
                    try {
                        targets.put(reference.image, publisher.apply(reference.image));
                    } catch (RuntimeException ignored) {
                        targets.put(reference.image, null);
                    }
                }
                target = targets.get(reference.image);
            }
            String replacement = reference.fallback;
            if (target != null && !target.trim().isEmpty()) {
                replacement = (link ? "[" : "![") + escapeAlt(reference.alt.isEmpty() ? "图片" : reference.alt)
                        + "](" + target + ")";
            }
            replacements.put(reference.start, new Replacement(reference.end, replacement,
                    replacement.equals(reference.fallback) ? null : reference.fallback));
        }
        StringBuilder rendered = new StringBuilder();
        List<ImageRange> ranges = new ArrayList<>();
        int cursor = 0;
        for (Map.Entry<Integer, Replacement> entry : replacements.entrySet()) {
            if (entry.getKey() < cursor) continue;
            Replacement replacement = entry.getValue();
            rendered.append(text, cursor, entry.getKey());
            int start = rendered.length();
            rendered.append(replacement.text);
            if (replacement.fallback != null) {
                ranges.add(new ImageRange(start, rendered.length(), replacement.fallback));
            }
            cursor = replacement.end;
        }
        return new RenderedImages(rendered.append(text, cursor, text.length()).toString(), ranges);
    }

    /** 渠道替换后的 Markdown 与真实节点范围；不暴露通用 AST。 */
    public static final class RenderedImages {
        private final String text;
        private final List<ImageRange> ranges;

        private RenderedImages(String text, List<ImageRange> ranges) {
            this.text = text;
            this.ranges = Collections.unmodifiableList(new ArrayList<>(ranges));
        }

        public String getText() { return text; }
        public List<ImageRange> getRanges() { return ranges; }
    }

    /** 输出字符串的 UTF-16 半开区间；fallback 不含平台 ID 或外链。 */
    public static final class ImageRange {
        private final int start;
        private final int end;
        private final String fallback;

        private ImageRange(int start, int end, String fallback) {
            this.start = start;
            this.end = end;
            this.fallback = fallback;
        }

        public int getStart() { return start; }
        public int getEnd() { return end; }
        public String getFallback() { return fallback; }
    }

    private static final class Replacement {
        final int end;
        final String text;
        final String fallback;
        Replacement(int end, String text) { this(end, text, null); }
        Replacement(int end, String text, String fallback) {
            this.end = end;
            this.text = text;
            this.fallback = fallback;
        }
    }

    private static String safeExternalUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || value.indexOf('\\') >= 0) return null;
            // Markdown 目标中的括号必须编码；URI 不执行网络访问。
            return scheme.toLowerCase(Locale.ROOT) + value.substring(value.indexOf(':'))
                    .replace("(", "%28").replace(")", "%29");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 仅保留短的人类可读标签；疑似路径、凭据或机器标识一律使用固定标签。 */
    private static String safeAlt(String alt) {
        if (alt == null || alt.length() > 100 || alt.matches("(?s).*[\\\\/=:?%].*")
                || alt.matches("(?is).*\\b(token|secret|password|bearer|api[_-]?key)\\b.*")
                || alt.matches("(?s).*[A-Za-z0-9_-]{24,}.*")) return "";
        return alt.replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    private static String escapeAlt(String alt) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < alt.length(); i++) {
            char c = alt.charAt(i);
            if ("\\`*_{}[]()#+-.!<>|&".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** start/end 为源字符串的 UTF-16 半开区间；外链不具有本地快照。 */
    public static final class ImageReference {
        private final int start;
        private final int end;
        private final String alt;
        private final PublishedImage image;
        private final String externalUrl;
        private final String fallback;

        private ImageReference(int start, int end, String alt, PublishedImage image, String externalUrl, String reason) {
            this.start = start;
            this.end = end;
            this.alt = alt;
            this.image = image;
            this.externalUrl = externalUrl;
            this.fallback = "（图片不可用" + (alt.isEmpty() ? "" : "：" + escapeAlt(alt)) + "；" + reason + "）";
        }

        public int getStart() { return start; }
        public int getEnd() { return end; }
        public String getAlt() { return alt; }
        public PublishedImage getImage() { return image; }
        public String getExternalUrl() { return externalUrl; }
        public String getFallback() { return fallback; }
    }
}
