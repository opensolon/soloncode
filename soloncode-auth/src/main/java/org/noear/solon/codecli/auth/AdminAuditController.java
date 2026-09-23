package org.noear.solon.codecli.auth;

import org.noear.solon.annotation.Get;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Result;

import java.util.Map;

/** 管理审计只读查询；路径鉴权由已有 UserAuthFilter 统一执行。 */
public class AdminAuditController {
    private final AdminAuditStore store;

    public AdminAuditController(AdminAuditStore store) {
        this.store = store;
    }

    @Get
    @Mapping("/web/admin/audit")
    public Result<Map<String, Object>> query(
            @Param(value = "page", required = false, defaultValue = "1") Integer page,
            @Param(value = "pageSize", required = false, defaultValue = "20") Integer pageSize,
            @Param(value = "event", required = false) String event,
            @Param(value = "field", required = false) String field,
            @Param(value = "value", required = false) String value) {
        return Result.succeed(store.query(page == null ? 1 : page, pageSize == null ? 20 : pageSize,
                event, field, value));
    }
}
