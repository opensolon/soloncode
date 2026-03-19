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
package org.noear.solon.bot.core.memory.bank;

/**
 * 向量化服务（Embedding Service）
 * <p>
 * 用于将文本转换为向量，支持语义检索
 * <p>
 * 实现方式：
 * - 使用 OpenAI Embeddings API
 * - 使用本地模型（如 sentence-transformers）
 * - 使用第三方服务（如 HuggingFace）
 *
 * @author bai
 * @since 3.9.5
 */
public interface EmbeddingService {

    /**
     * 将文本转换为向量
     *
     * @param text 输入文本
     * @return 向量（float 数组）
     */
    float[] embed(String text);

    /**
     * 计算两个向量的余弦相似度
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 相似度（0.0-1.0）
     */
    default double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
