package org.noear.solon.codecli.portal.web;

import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Filter;
import org.noear.solon.core.handle.FilterChain;

/**
 * Web 访问认证过滤器（Basic Auth）。
 *
 * <p>已废弃。旧版 Basic Auth 仅由迁移服务读取，不再注册为过滤器。
 * 当前 Web 认证统一由 {@code UserAuthFilter} 处理。</p>
 *
 * @author noear 2026/6/23 created
 */
@Deprecated
public class WebAuthFilter implements Filter {

    @Override
    public void doFilter(Context ctx, FilterChain chain) throws Throwable {
        // 保留类仅为二进制兼容；正式认证由 UserAuthFilter 统一处理。
        chain.doFilter(ctx);
    }
}