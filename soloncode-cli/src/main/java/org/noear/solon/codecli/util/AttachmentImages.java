/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.ImageInputs;

/**
 * 用户上传的图片附件（Web、桌面、ACP）：与 read 读图同一套缩放、转码规则（{@link ImageInputs}）。
 *
 * <p>处理失败（解不了、缩不下去）时保留原图，不悄悄丢掉用户的附件：
 * 超过单张上限的由对话方言换成说明文本。</p>
 *
 * @author noear
 * @since 2026.11
 */
public final class AttachmentImages {
    private AttachmentImages() {
    }

    /**
     * @param base64 图片数据（不带 data: 前缀）
     * @param mime   声明的类型；成功处理后以文件头识别的类型为准
     */
    public static ImageBlock of(String base64, String mime) {
        ContentBlock block = ImageInputs.normalizeBase64(base64, mime);
        if (block instanceof ImageBlock) {
            return (ImageBlock) block;
        }
        return ImageBlock.ofBase64(base64, mime);
    }
}
