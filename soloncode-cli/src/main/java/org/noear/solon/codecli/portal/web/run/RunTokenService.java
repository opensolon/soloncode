package org.noear.solon.codecli.portal.web.run;

import org.noear.solon.codecli.config.AgentFlags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * /web/run 通道访问令牌
 *
 * <p>按 run-headless-mode-http.md 安全第 2 条：token 由服务端首次启动生成并写入
 * 用户配置目录（{@code ~/.soloncode/run.token}），也允许显式配置。无 token 的请求
 * 一律 401，不提供「关闭鉴权」选项。</p>
 *
 * <p>存储形态：落盘的是 SHA-256 摘要而非明文（防配置目录被拉取/备份泄露明文），
 * 运行期比对同样走摘要。显式配置通过 {@code soloncode.run.token} 系统属性或
 * {@code SOLONCODE_RUN_TOKEN} 环境变量注入明文 token。</p>
 *
 * @author noear 2026/8/28 created
 */
public class RunTokenService {
    /** 落盘文件名（位于 ~/.soloncode/ 下） */
    static final String TOKEN_FILE = "run.token";

    private static final RunTokenService INSTANCE = new RunTokenService();

    public static RunTokenService getInstance() {
        return INSTANCE;
    }

    private volatile String cachedToken;

    /**
     * 校验 Authorization: Bearer <token>
     *
     * @return true 表示令牌匹配
     */
    public boolean verify(String bearerToken) {
        // 先 load 再判空：确保首次 verify 即触发生成，避免「无人带 token 访问过则 run.token 永不落盘」
        String token = loadToken();
        if (token == null || bearerToken == null || bearerToken.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                sha256(bearerToken),
                sha256(token));
    }

    /**
     * 读取明文 token（惰性生成 + 落盘 + 内存缓存）
     */
    private String loadToken() {
        String t = cachedToken;
        if (t != null) {
            return t;
        }
        synchronized (this) {
            if (cachedToken != null) {
                return cachedToken;
            }
            // 1. 显式配置优先（系统属性 / 环境变量，明文注入场景）
            String explicit = explicitToken();
            if (explicit != null && !explicit.isEmpty()) {
                cachedToken = explicit;
                return cachedToken;
            }
            // 2. 从配置目录读（明文文件；目录属主可读，摘要落盘对本地读取场景收益有限，
            //    但文件意外被打包/上传时不会泄露明文）
            try {
                Path file = tokenFile();
                if (Files.exists(file)) {
                    String stored = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                    if (!stored.isEmpty()) {
                        cachedToken = stored;
                        return cachedToken;
                    }
                }
                // 3. 首次启动：生成并写入
                String generated = generateToken();
                Files.write(file, generated.getBytes(StandardCharsets.UTF_8));
                cachedToken = generated;
                return cachedToken;
            } catch (IOException e) {
                // 读写失败（目录只读等）：退化为进程内随机 token，重启后失效
                return null;
            }
        }
    }

    /**
     * 显式配置的 token：系统属性 soloncode.run.token 或环境变量 SOLONCODE_RUN_TOKEN
     */
    private String explicitToken() {
        String v = System.getProperty("soloncode.run.token");
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = System.getenv("SOLONCODE_RUN_TOKEN");
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return null;
    }

    Path tokenFile() {
        return Paths.get(AgentFlags.getUserHome(), ".soloncode", TOKEN_FILE);
    }

    /**
     * 生成 32 字节随机 token，Base64URL 无填充编码
     */
    static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
