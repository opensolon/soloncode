package org.noear.solon.codecli.config;

import org.noear.solon.Solon;
import org.noear.solon.Utils;
import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * 宿主级默认语言：显式配置的 {@code solon.locale}（启动参数 {@code --locale=en} 或配置文件）。
 *
 * <p>引擎语言（{@code Builder.locale}）与 IM 回执共用它：配置了语言时，发给模型的文字与宿主的提示同一语言。</p>
 *
 * <p>只认显式配置，不跟 JVM 默认地区走：没配置时引擎用全局默认语言（中文，与此前逐字一致）。
 * 服务器、容器的 JVM 默认常是 en_US，跟着它走会让中文用户发给模型的提示词整体变成英文。</p>
 *
 * <p>Web 端每轮带界面语言，优先于这里；这里管不带语言的入口（CLI、headless、ACP、IM、桌面）。</p>
 *
 * @author noear 2026/11/13 created
 */
public final class HostLocale {
    private HostLocale() {
    }

    /**
     * 显式配置的语言（没配置为 null）
     */
    public static @Nullable Locale configured() {
        try {
            String tag = Solon.cfg().argx().get("locale");
            if (Utils.isEmpty(tag)) {
                tag = Solon.cfg().get("solon.locale");
            }
            return Utils.isEmpty(tag) ? null : Utils.toLocale(tag);
        } catch (Throwable e) {
            // Solon 未初始化（单测、嵌入式）：按没配置处理
            return null;
        }
    }
}
