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
package org.noear.solon.codecli.util;

import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.core.handle.Result;

/**
 * 插件门面结果（{@link HarnessResult}）到 HTTP {@link Result} 的统一映射。
 *
 * <p>所有控制器共用这一张码表，避免各处各写一套状态码：</p>
 * <ul>
 *   <li>OK → 200</li>
 *   <li>NOT_FOUND → 404</li>
 *   <li>NOT_OWNER → 409（单机桌面/Web 不做转发，按冲突返回）</li>
 *   <li>DISABLED → 503</li>
 *   <li>FULL → 429</li>
 *   <li>INVALID → 409（参数类错误；控制器需要 400 时在调用门面前自行判定，保持既有应答不变）</li>
 *   <li>CONFLICT → 409（状态不允许）</li>
 *   <li>FORBIDDEN → 403</li>
 * </ul>
 *
 * @author noear
 * @since 2026.11
 */
public final class HarnessResultUtil {
    private HarnessResultUtil() {
    }

    /** 失败结果对应的 HTTP 状态码（OK 为 200） */
    public static int httpStatus(HarnessResult<?> result) {
        switch (result.getCode()) {
            case OK:
                return 200;
            case NOT_FOUND:
                return 404;
            case DISABLED:
                return 503;
            case FULL:
                return 429;
            case FORBIDDEN:
                return 403;
            case NOT_OWNER:
            case INVALID:
            case CONFLICT:
            default:
                return 409;
        }
    }

    /** 失败结果转为 HTTP 失败（detail 为空时用 fallback） */
    public static <T> Result<T> failure(HarnessResult<?> result, String fallback) {
        String detail = result.getDetail();
        return Result.failure(httpStatus(result), detail == null || detail.isEmpty() ? fallback : detail);
    }

    /** OK 时返回无数据的成功，否则按码表转失败 */
    public static <T> Result<T> toResult(HarnessResult<?> result, String fallback) {
        return result.isOk() ? Result.<T>succeed() : HarnessResultUtil.<T>failure(result, fallback);
    }
}
