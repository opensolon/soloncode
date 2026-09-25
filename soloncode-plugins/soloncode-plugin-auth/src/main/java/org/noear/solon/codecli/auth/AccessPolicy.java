package org.noear.solon.codecli.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 管理台与工作台的来源地址访问策略。第一版支持单个 IP，多地址白名单，不支持 CIDR。 */
public final class AccessPolicy {
    public static final String LOCAL = "local";
    public static final String ALLOWLIST = "allowlist";
    public static final String ANY = "any";
    /** 旧版工作台模式，读取时转换为 any。 */
    public static final String AUTHENTICATED = "authenticated";

    private AccessPolicy() { }

    public static String normalizeMode(String mode, boolean admin) {
        if (admin) {
            // 旧版管理台 any 不再提供：归一化为仅限本机，避免隐式继续对外开放。
            if (LOCAL.equals(mode) || ALLOWLIST.equals(mode)) return mode;
            return LOCAL;
        }
        if (LOCAL.equals(mode) || ALLOWLIST.equals(mode)) return mode;
        // 旧版 authenticated 仅表示来源不限；登录与否由认证配置决定。
        return ANY;
    }

    public static List<String> normalizeAllowlist(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            result.add(normalizeIp(value));
        }
        return new ArrayList<>(result);
    }

    public static String normalizeIp(String value) {
        if (value == null) throw new IllegalArgumentException("IP 地址不能为空");
        String text = value.trim();
        if (text.isEmpty() || text.indexOf('%') >= 0 || !looksLikeIpLiteral(text)) {
            throw new IllegalArgumentException("仅支持 IPv4 或 IPv6 地址: " + value);
        }
        try {
            return InetAddress.getByName(text).getHostAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("IP 地址无效: " + value, e);
        }
    }

    public static boolean isAllowed(String mode, List<String> allowlist, String remoteIp, boolean admin) {
        String normalized = normalizeMode(mode, admin);
        if (ANY.equals(normalized)) return true;
        if (remoteIp == null) return false;
        if (LOCAL.equals(normalized)) return isLoopback(remoteIp);
        if (allowlist == null || allowlist.isEmpty()) return false;
        try {
            byte[] actual = InetAddress.getByName(remoteIp).getAddress();
            for (String allowed : allowlist) {
                if (allowed == null) continue;
                if (java.util.Arrays.equals(actual, InetAddress.getByName(allowed).getAddress())) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    public static boolean isLoopback(String ip) {
        if (ip == null) return false;
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksLikeIpLiteral(String value) {
        if (value.indexOf(':') >= 0) return true;
        return value.matches("[0-9.]+");
    }
}
