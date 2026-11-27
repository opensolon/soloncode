package org.noear.solon.codecli.model.discovery;

import org.noear.snack4.ONode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 模型可输入类型的归一化。
 * <p>
 * 来源（models.dev、供应商接口）写法不一：有的写 text/image，有的写 MIME 类型（image/png、application/pdf）。
 * 这里按 opencode 的 modality() 规则取大类，只保留框架能处理的 text/image/audio/video 四种
 * （pdf 等文档类型没有对应的媒体块，保留下来也不起作用），去重并保持原顺序。
 */
public final class ModelModalities {
    public static final String TEXT = "text";
    public static final String IMAGE = "image";

    private ModelModalities() {
    }

    /**
     * 归一化；没有任何可识别的类型时返回 null（表示不知道，交给下一个来源或保持不限制）
     */
    public static List<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        Set<String> kinds = new LinkedHashSet<>();
        for (String value : values) {
            String kind = kindOf(value);
            if (kind != null) {
                kinds.add(kind);
            }
        }

        return kinds.isEmpty() ? null : new ArrayList<>(kinds);
    }

    /**
     * 从 JSON 数组归一化；不是数组或没有可识别的类型时返回 null
     */
    public static List<String> of(ONode node) {
        if (node == null || node.isArray() == false) {
            return null;
        }

        List<String> values = new ArrayList<>();
        for (ONode item : node.getArray()) {
            values.add(item.getString());
        }
        return normalize(values);
    }

    /**
     * 按是否能看图给出可输入类型（Anthropic image_input、Ollama vision 这类布尔能力）
     */
    public static List<String> ofVision(boolean vision) {
        List<String> list = new ArrayList<>();
        list.add(TEXT);
        if (vision) {
            list.add(IMAGE);
        }
        return list;
    }

    private static String kindOf(String value) {
        if (value == null) {
            return null;
        }

        String v = value.trim().toLowerCase(Locale.ROOT);
        int idx = v.indexOf('/');
        if (idx > 0) {
            v = v.substring(0, idx);
        }

        switch (v) {
            case "text":
            case "image":
            case "audio":
            case "video":
                return v;
            default:
                return null;
        }
    }
}
