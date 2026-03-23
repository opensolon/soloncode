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
package org.noear.solon.codecli.core.teams.memory;

import lombok.Getter;

import java.util.List;
import java.util.ArrayList;

/**
 * 知识库记忆（架构知识、模式等）
 *
 * @author bai
 * @since 3.9.5
 */
@Getter
public class KnowledgeMemory extends Memory {
    private String subject;      // 主题
    private String content;      // 内容
    private String category;     // 分类（architecture/pattern/api）
    private List<String> keywords; // 关键词

    /**
     * 无参构造函数（用于反序列化）
     */
    public KnowledgeMemory() {
        super(MemoryType.KNOWLEDGE, -1);
        this.subject = "";
        this.content = "";
        this.category = "";
        this.keywords = new ArrayList<>();
    }

    /**
     * 构造函数
     *
     * @param subject 主题
     * @param content 内容
     * @param category 分类
     */
    public KnowledgeMemory(String subject, String content, String category) {
        super(MemoryType.KNOWLEDGE, -1); // 永久，无TTL
        this.subject = subject;
        this.content = content;
        this.category = category;
        this.keywords = new ArrayList<>();
    }

    /**
     * 构造函数（带关键词）
     */
    public KnowledgeMemory(String subject, String content, String category, List<String> keywords) {
        super(MemoryType.KNOWLEDGE, -1);
        this.subject = subject;
        this.content = content;
        this.category = category;
        this.keywords = keywords != null ? new ArrayList<>(keywords) : new ArrayList<>();
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords != null ? new ArrayList<>(keywords) : new ArrayList<>();
    }
}
