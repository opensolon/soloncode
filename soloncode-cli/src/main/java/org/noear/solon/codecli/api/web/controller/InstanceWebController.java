package org.noear.solon.codecli.api.web.controller;

import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.codecli.api.web.AbstractWebController;

import org.noear.solon.codecli.workspace.WorkspaceContext;
import org.noear.solon.codecli.workspace.WorkspaceManager;
import org.noear.solon.codecli.workspace.WorkspaceMeta;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import org.noear.snack4.ONode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本机 launcher 向已运行 Web 实例交接工作区的轻量协议。 */
public class InstanceWebController extends AbstractWebController {
    private final String attachToken;
    private volatile boolean attachSupported;
    
    public InstanceWebController(WorkspaceManager workspaceManager) {
        this(workspaceManager, null);
    }
    
    public InstanceWebController(WorkspaceManager workspaceManager, String attachToken) {
        super(workspaceManager);
        this.attachToken = attachToken;
    }
    
    /** 仅在服务启动且凭据发布成功后启用交接。 */
    public void setAttachSupported(boolean supported) {
        attachSupported = supported && attachToken != null && !attachToken.trim().isEmpty();
    }

    @Get
    @Mapping("/web/instance/info")
    public Result<Map<String, Object>> info() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", "soloncode");
        data.put("protocol", 1);
        data.put("attachSupported", attachSupported);
        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/instance/attach")
    public Result<Map<String, Object>> attach(Context ctx, @Body String body) {
        if (!isLoopback(ctx)) return Result.failure("仅限本机调用（loopback）");
        String expected = attachToken;
        String supplied = ctx.header("X-Soloncode-Attach-Token");
        if (!attachSupported || expected == null || supplied == null || !constantTimeEquals(expected, supplied)) {
            return Result.failure("attach 凭据无效");
        }

        try {
            String path = ONode.ofJson(body == null ? "{}" : body).get("path").getString();
            if (path == null || path.trim().isEmpty()) return Result.failure("Path is required");
            Path requested = Paths.get(path);
            if (!requested.isAbsolute()) return Result.failure("Path must be absolute");
            Path target = requested.normalize().toRealPath();
            if (!Files.isDirectory(target)) return Result.failure("目录不存在: " + path);
            WorkspaceContext workspace = workspaceManager.getOrCreate(target.toString());
            if (workspace == null || workspace.getMeta() == null) return Result.failure("无法创建工作区: " + path);
            WorkspaceMeta meta = workspace.getMeta();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("workspaceId", meta.getId());
            data.put("workspace", meta);
            return Result.succeed(data);
        } catch (Exception e) {
            return Result.failure("打开工作区失败: " + e.getMessage());
        }
    }

    

    private static boolean isLoopback(Context ctx) {
        if (ctx == null) return false;
        // attach 是本机进程协议，必须使用实际 TCP 对端地址，不能信任代理转发头。
        String ip = ctx.remoteIp();
        return "127.0.0.1".equals(ip) || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
