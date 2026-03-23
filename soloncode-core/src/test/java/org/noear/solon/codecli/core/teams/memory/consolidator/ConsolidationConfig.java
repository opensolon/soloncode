package org.noear.solon.codecli.core.teams.memory.consolidator;

/**
 * 记忆合并配置
 */
public class ConsolidationConfig {
    public double similarityThreshold = 0.85;  // 相似度阈值
    public int maxGroupSize = 5;                 // 每组最大记忆数
    public boolean mergeContent = true;           // 是否合并内容
    public boolean keepMetadata = true;           // 是否保留元数据
}