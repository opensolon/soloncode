package org.noear.solon.codecli.auth;

import org.noear.snack4.ONode;
import org.noear.solon.annotation.Body;
import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.annotation.Post;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端会话管理；管理员身份由 UserAuthFilter 对 /web/admin/ 路径统一校验。 */
public class AdminSessionManagementController {
    private static final int MAX_PAGE_SIZE = 100;
    private final UserSessionManager sessionManager;

    public AdminSessionManagementController(UserSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Get
    @Mapping("/web/admin/sessions")
    public Result<Map<String, Object>> list(
            @Param(value = "page", required = false, defaultValue = "1") Integer page,
            @Param(value = "pageSize", required = false, defaultValue = "20") Integer pageSize) {
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            return Result.failure("分页参数无效：page 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
        List<UserSessionManager.SessionSummary> sessions = sessionManager.listActiveSessions();
        sessions.sort(Comparator.comparingLong(UserSessionManager.SessionSummary::getCreatedAt).reversed()
                .thenComparing(UserSessionManager.SessionSummary::getId));
        long start = ((long) page - 1) * pageSize;
        int from = (int) Math.min(start, sessions.size());
        int to = (int) Math.min((long) from + pageSize, sessions.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("total", sessions.size());
        data.put("items", new ArrayList<>(sessions.subList(from, to)));
        return Result.succeed(data);
    }

    @Post
    @Mapping("/web/admin/sessions/revoke")
    public Result<Map<String, Object>> revoke(Context ctx, @Body String json) {
        ONode root = parseBody(json);
        String id = root == null ? null : root.get("id").getString();
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            return failure("admin.session.revoke.failure", "invalid_id", "会话 ID 无效");
        }
        if (!sessionManager.revokeSessionById(id)) {
            return failure("admin.session.revoke.failure", "not_found", "会话不存在或已失效");
        }
        Map<String, Object> audit = auditActor(ctx);
        audit.put("revokedCount", 1);
        AdminAuditStore.record("admin.session.revoke.success", audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revokedCount", 1);
        return Result.succeed(result);
    }

    @Post
    @Mapping("/web/admin/sessions/revoke-user")
    public Result<Map<String, Object>> revokeUser(Context ctx, @Body String json) {
        ONode root = parseBody(json);
        String userId = root == null ? null : root.get("userId").getString();
        if (userId == null || userId.trim().isEmpty() || userId.length() > 128 || !userId.equals(userId.trim())) {
            return failure("admin.session.revoke_user.failure", "invalid_user_id", "用户 ID 无效");
        }
        int count = sessionManager.revokeUserSessions(userId);
        Map<String, Object> audit = auditActor(ctx);
        audit.put("targetUserId", userId);
        audit.put("revokedCount", count);
        AdminAuditStore.record("admin.session.revoke_user.success", audit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revokedCount", count);
        return Result.succeed(result);
    }

    private static ONode parseBody(String json) {
        if (json == null || json.length() > 4096) return null;
        try {
            ONode root = ONode.ofJson(json);
            return root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> auditActor(Context ctx) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (ctx != null) fields.put("actorUserId", ctx.attr("user_id"));
        return fields;
    }

    private static <T> Result<T> failure(String event, String reason, String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reason", reason);
        AdminAuditStore.record(event, fields);
        return Result.failure(message);
    }
}
