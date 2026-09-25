package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理控制台和工作台的来源地址访问策略。 */
public class AccessControlController {
    private final UserAuthConfig config;
    private final Object lock = new Object();

    public AccessControlController(UserAuthConfig config) {
        this.config = config;
    }

    @Get
    @Mapping("/web/admin/access-control")
    public Result<Map<String, Object>> get(Context ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentIp", ctx == null ? null : ctx.remoteIp());
        data.put("admin", policy(config.getAdminAccessMode(), config.getAdminIpAllowlist()));
        data.put("workspace", policy(config.getWorkspaceAccessMode(), config.getWorkspaceIpAllowlist()));
        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/admin/access-control/save")
    public Result<Map<String, Object>> save(Context ctx, @Body String json) {
        synchronized (lock) {
            UserAuthConfig old = UserAuthController.copyConfig(config);
            try {
                ONode root = ONode.ofJson(json == null ? "{}" : json);
                Policy admin = parsePolicy(root.get("admin"), true, config.getAdminAccessMode(), config.getAdminIpAllowlist());
                Policy workspace = parsePolicy(root.get("workspace"), false, config.getWorkspaceAccessMode(), config.getWorkspaceIpAllowlist());
                String currentIp = ctx == null ? null : ctx.remoteIp();
                if (AccessPolicy.ALLOWLIST.equals(admin.mode)
                        && !AccessPolicy.isAllowed(admin.mode, admin.allowlist, currentIp, true)) {
                    return Result.failure("当前访问地址不在新的管理控制台 IP 白名单中，保存后将无法继续访问管理控制台");
                }
                config.setAdminAccessMode(admin.mode);
                config.setAdminIpAllowlist(admin.allowlist);
                config.setWorkspaceAccessMode(workspace.mode);
                config.setWorkspaceIpAllowlist(workspace.allowlist);
                try {
                    AuthConfigRepository.save(config);
                } catch (Exception e) {
                    UserAuthController.applyConfig(config, old);
                    throw e;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("currentIp", currentIp);
                data.put("admin", policy(admin.mode, admin.allowlist));
                data.put("workspace", policy(workspace.mode, workspace.allowlist));
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("adminMode", admin.mode);
                audit.put("workspaceMode", workspace.mode);
                AdminAuditStore.record("access.policy.save.success", audit);
                return Result.succeed(data);
            } catch (Exception e) {
                UserAuthController.applyConfig(config, old);
                AdminAuditStore.record("access.policy.save.failure");
                return Result.failure("访问控制策略保存失败: " + safeMessage(e));
            }
        }
    }

    private static Policy parsePolicy(ONode node, boolean admin, String oldMode, List<String> oldList) {
        String mode = oldMode;
        List<String> list = oldList == null ? new ArrayList<String>() : new ArrayList<>(oldList);
        if (node != null && node.isObject()) {
            if (node.hasKey("mode")) mode = node.get("mode").getString();
            if (node.hasKey("allowlist")) {
                list.clear();
                ONode values = node.get("allowlist");
                if (!values.isArray()) throw new IllegalArgumentException("IP 白名单必须是数组");
                for (ONode value : values.getArray()) list.add(value.getString());
            }
        }
        mode = AccessPolicy.normalizeMode(mode, admin);
        list = AccessPolicy.normalizeAllowlist(list);
        if (AccessPolicy.ALLOWLIST.equals(mode) && list.isEmpty()) {
            throw new IllegalArgumentException("IP 白名单不能为空");
        }
        return new Policy(mode, list);
    }

    private static Map<String, Object> policy(String mode, List<String> allowlist) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("allowlist", allowlist == null ? new ArrayList<String>() : new ArrayList<>(allowlist));
        return result;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class Policy {
        private final String mode;
        private final List<String> allowlist;
        private Policy(String mode, List<String> allowlist) {
            this.mode = mode;
            this.allowlist = allowlist;
        }
    }
}
