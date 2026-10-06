/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.channel;

import org.noear.solon.Solon;
import org.noear.solon.i18n.I18nBundle;
import org.noear.solon.i18n.I18nUtil;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * IM 交互文案统一出口。
 *
 * <p>此前微信/飞书/钉钉各自维护 busy 提示等文案，容易分叉。收敛到此处后，
 * 三端（以及未来新增通道）复用同一份文案，{@link Channel#sendStatus} 的
 * detail 为空时由 {@link #textOf(ImStatus, String)} 兜底。</p>
 *
 * <p>文案面向 IM 使用场景，按聊天语气撰写：第一人称、口语、短句、不用
 * 「队列/位次/阈值」等系统术语，让用户一眼看懂「我这条消息怎么样了」。</p>
 *
 * <p>忙态回执还要交代「默认语义」：IM 忙时直发是「新任务排在后面」，web 忙时回车是
 * 「插话到当前任务」，两端默认不同，用户无从推断。故入队回执主动点明排队语义，
 * 并给出插话入口（/steer）与中断入口（/interrupt）；但同一轮只教学一次。</p>
 *
 * <p>文案已国际化：正文放在 classpath 的 {@code i18n/im-messages*.properties}，
 * 由 solon i18n 按 {@link #getLocale()} 解析。为了让「文案可随地区变化」，
 * 本类对外由常量改为方法（名字保持不变，调用点补一对括号即可）。</p>
 *
 * @author noear 2026/5/9 created
 */
public final class ImMessages {
    /**
     * 资源包名，对应 classpath 下的 {@code i18n/im-messages*.properties}。
     *
     * <p>无后缀文件即「默认语言」（简体中文），其它语言以 {@code _<lang>} 覆盖。</p>
     */
    public static final String BUNDLE_NAME = "i18n.im-messages";

    /**
     * 空闲受理成功
     */
    private static final String KEY_ACCEPTED = "im.accepted";
    /**
     * 长任务心跳
     */
    private static final String KEY_LONG_RUNNING = "im.longRunning";
    /**
     * 入队失败（队列满等）
     */
    private static final String KEY_REJECTED = "im.rejected";
    /**
     * 未绑定用户引导
     */
    private static final String KEY_HINT_UNBOUND = "im.hint.unbound";
    /**
     * bot 已被别的对话占用时的引导
     */
    private static final String KEY_HINT_BOT_TAKEN = "im.hint.botTaken";
    /**
     * 非文本消息回执
     */
    private static final String KEY_HINT_NON_TEXT = "im.hint.nonText";
    /**
     * 忙态插话引导
     */
    private static final String KEY_HINT_STEER = "im.hint.steer";
    /**
     * 忙态中断引导
     */
    private static final String KEY_HINT_INTERRUPT = "im.hint.interrupt";
    /**
     * 忙态命令引导组合
     */
    private static final String KEY_HINT_BUSY_COMMAND = "im.hint.busyCommand";
    /**
     * 入队回执（前面还有人）
     */
    private static final String KEY_QUEUED_BEHIND = "im.queued.behind";
    /**
     * 入队回执（当前任务完成后即轮到）
     */
    private static final String KEY_QUEUED_IMMEDIATE = "im.queued.immediate";

    /**
     * 兜底文案，与资源包默认语言保持一致。
     *
     * <p>资源包缺失或加载失败时仍给出可读文本，绝不让资源键或占位符泄漏给用户；
     * 同时也是「改文案不改代码」时的安全网。增删资源键请同步这里。</p>
     */
    private static final Map<String, String> FALLBACK = new HashMap<String, String>();

    static {
        FALLBACK.put(KEY_ACCEPTED, "收到，马上开始处理");
        FALLBACK.put(KEY_LONG_RUNNING, "还在处理中，请再稍等一下");
        FALLBACK.put(KEY_REJECTED, "这条消息没排上队，我还没收到，稍后请重发一次。想先中断当前任务，可发送 /interrupt");
        FALLBACK.put(KEY_HINT_UNBOUND, "还没有绑定对话，请先在 Web 端扫码绑定，然后我就能陪你聊了。");
        FALLBACK.put(KEY_HINT_BOT_TAKEN, "这个机器人已经绑定到别的对话了。想接到当前对话，请先在 Web 端解绑，再重新扫码绑定。");
        FALLBACK.put(KEY_HINT_NON_TEXT, "我暂时只看得懂文字，换文字发给我吧。");
        FALLBACK.put(KEY_HINT_STEER, "想补充或调整当前任务，可发送 /steer <内容>");
        FALLBACK.put(KEY_HINT_INTERRUPT, "想中断当前任务，可发送 /interrupt");
        FALLBACK.put(KEY_HINT_BUSY_COMMAND, "{0}；{1}");
        FALLBACK.put(KEY_QUEUED_BEHIND, "收到，已作为新任务排队，前面还有 {1} 条，处理完就轮到你。{0}");
        FALLBACK.put(KEY_QUEUED_IMMEDIATE, "收到，已作为新任务排队，当前任务完成后就轮到你。{0}");
    }

    /**
     * 显式地区；非空时优先于运行时解析（供测试与嵌入式场景固定语言）
     */
    private static volatile Locale localeOverride;

    private ImMessages() {
    }

    /**
     * 固定文案地区；传 null 表示恢复运行时解析。
     */
    public static void setLocale(Locale locale) {
        localeOverride = locale;
    }

    /**
     * 当前文案地区：显式指定 &gt; solon.locale 配置 &gt; JVM 默认。
     */
    public static Locale getLocale() {
        Locale locale = localeOverride;
        if (locale != null) {
            return locale;
        }

        try {
            locale = Solon.cfg().locale();
        } catch (Throwable ignored) {
            // Solon 未初始化等异常场景，落到 JVM 默认地区
        }

        return locale == null ? Locale.getDefault() : locale;
    }

    /**
     * 空闲受理成功
     */
    public static String ACCEPTED() {
        return msg(KEY_ACCEPTED);
    }

    /**
     * 长任务心跳
     */
    public static String LONG_RUNNING() {
        return msg(KEY_LONG_RUNNING);
    }

    /**
     * 入队失败（队列满等）：明确告知本轮未被接收、稍后需重发；此时唯一能自救的是
     * 中断当前任务，故直接给出命令。
     */
    public static String REJECTED() {
        return msg(KEY_REJECTED);
    }

    /**
     * 未绑定用户引导
     */
    public static String HINT_UNBOUND() {
        return msg(KEY_HINT_UNBOUND);
    }

    /** bot 已被别的对话占用时的引导。 */
    public static String HINT_BOT_TAKEN() {
        return msg(KEY_HINT_BOT_TAKEN);
    }

    /**
     * 非文本消息回执
     */
    public static String HINT_NON_TEXT() {
        return msg(KEY_HINT_NON_TEXT);
    }

    /**
     * 忙态插话引导
     */
    public static String STEER_HINT() {
        return msg(KEY_HINT_STEER);
    }

    /**
     * 忙态中断引导
     */
    public static String INTERRUPT_HINT() {
        return msg(KEY_HINT_INTERRUPT);
    }

    /**
     * 忙态命令引导（插话 + 中断），附在首条入队回执末尾。
     *
     * <p>这里刻意不引导 /queue：IM 忙态直发消息即自动排队，再教一个显式排队命令，
     * 反而会让用户以为「不敲命令消息就会丢」。/steer 才是 IM 真正缺的能力补位。</p>
     */
    public static String BUSY_COMMAND_HINT() {
        return msg(KEY_HINT_BUSY_COMMAND, STEER_HINT(), INTERRUPT_HINT());
    }

    /**
     * 入队回执：带上排队位次，并给出忙态命令引导。
     *
     * @param ahead 前面还有多少条待执行
     */
    public static String queued(int ahead) {
        return queued(ahead, true);
    }

    /**
     * 入队回执。
     *
     * <p>必须点明「已作为新任务排队」：忙态直发不是插话，不说清用户会以为自己的补充
     * 已经进了当前任务。</p>
     *
     * @param ahead           前面还有多少条待执行
     * @param withCommandHint 是否附上命令引导（同一轮忙态只提示一次）
     */
    public static String queued(int ahead, boolean withCommandHint) {
        int n = Math.max(0, ahead);
        // 命令引导为空串时 {0} 位置自然留白，中英文都不必特判
        String hint = withCommandHint ? BUSY_COMMAND_HINT() : "";

        if (n == 0) {
            // 前面没别的待执行；但当前任务仍在跑，须点明「当前任务完成后才轮到」，
            // 不能让用户以为马上开始，也不必提「还有 0 条」
            return msg(KEY_QUEUED_IMMEDIATE, hint);
        }

        // 数字以字符串传入，避免 MessageFormat 按地区加千分位
        return msg(KEY_QUEUED_BEHIND, hint, String.valueOf(n));
    }

    /**
     * 解析状态对应的默认文案；detail 非空时优先采用 detail。
     *
     * @return null 表示该状态无需文本（由通道自行决定是否忽略）
     */
    public static String textOf(ImStatus status, String detail) {
        if (detail != null && !detail.trim().isEmpty()) {
            return detail;
        }
        if (status == null) {
            return null;
        }
        switch (status) {
            case ACCEPTED:
                return ACCEPTED();
            case LONG_RUNNING:
                return LONG_RUNNING();
            case REJECTED:
                return REJECTED();
            default:
                // QUEUED 需要位次，必须由调用方提供 detail
                return null;
        }
    }

    /**
     * 按当前地区解析文案，资源包缺失时回退到 {@link #FALLBACK}。
     */
    private static String msg(String key, Object... args) {
        Locale locale = getLocale();
        String pattern = null;

        try {
            I18nBundle bundle = I18nUtil.getBundle(BUNDLE_NAME, locale);
            pattern = bundle.get(key);
        } catch (Throwable ignored) {
            // 资源缺失/加载失败：走兜底文案
        }

        if (pattern == null) {
            pattern = FALLBACK.get(key);
        }
        if (pattern == null) {
            // 兜底也没有（键写错），退回键名以便定位，不让调用方拿到 null
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }

        // 占位符可能被替换为空串（如无需命令引导时），去掉因此产生的首尾空白
        return new MessageFormat(pattern, locale).format(args).trim();
    }
}
