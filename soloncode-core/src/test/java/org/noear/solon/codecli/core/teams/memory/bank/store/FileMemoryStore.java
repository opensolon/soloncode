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
package org.noear.solon.codecli.core.teams.memory.bank.store;

import org.noear.snack4.ONode;
import org.noear.solon.codecli.core.teams.memory.bank.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件存储实现（MemoryBank）
 *
 * @author bai
 * @since 3.9.5
 */
public class FileMemoryStore implements MemoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(FileMemoryStore.class);

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;
    private static final String TEMP_FILE_SUFFIX = ".tmp";

    private final String storePath;

    public FileMemoryStore(String workDir) {
        // workDir 应该已经是完整的记忆存储路径（例如：workDir/.soloncode/memory）
        // 不再重复拼接 SOLONCODE_MEMORY
        this.storePath = workDir;

        // 确保目录存在
        try {
            File dir = new File(storePath);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (created) {
                    LOG.info("创建记忆存储目录: {}", storePath);
                }
            }

            // 验证目录是否可写
            if (!dir.isDirectory()) {
                LOG.error("路径不是目录: {}", storePath);
            } else if (!dir.canWrite()) {
                LOG.warn("目录不可写: {}", storePath);
            }

        } catch (Exception e) {
            LOG.error("初始化文件存储失败: path={}, error={}", storePath, e.getMessage(), e);
        }

        LOG.info("MemoryBank 文件存储初始化完成: path={}", storePath);
    }

    @Override
    public void store(Observation observation) {
        if (observation == null || observation.getId() == null) {
            return;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                storeWithAtomicWrite(observation);
                LOG.debug("Observation 已存储: id={}, attempt={}",
                        observation.getId(), attempt + 1);
                return;
            } catch (Throwable e) {
                boolean isLastAttempt = (attempt == MAX_RETRIES - 1);

                if (isLastAttempt) {
                    LOG.error("Observation 存储失败（已重试{}次）: id={}, error={}",
                            MAX_RETRIES, observation.getId(), e.getMessage());
                } else {
                    LOG.warn("Observation 存储失败（重试中）: id={}, attempt={}, error={}",
                            observation.getId(), attempt + 1, e.getMessage());
                }

                if (!isLastAttempt) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void storeWithAtomicWrite(Observation observation) throws Exception {
        String fileName = observation.getId() + ".json";
        String tempFilePath = storePath + File.separator + fileName + TEMP_FILE_SUFFIX;
        String targetFilePath = storePath + File.separator + fileName;

        // 序列化为 JSON
        String json = ONode.serialize(observation);

        // 写入临时文件
        Files.write(Paths.get(tempFilePath), json.getBytes(StandardCharsets.UTF_8));

        // 原子性地重命名
        Files.move(
                Paths.get(tempFilePath),
                Paths.get(targetFilePath),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
    }

    @Override
    public Observation load(String id) {
        try {
            String filePath = storePath + File.separator + id + ".json";
            File file = new File(filePath);

            if (!file.exists()) {
                return null;
            }

            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return ONode.deserialize(json, Observation.class);

        } catch (Throwable e) {
            LOG.debug("Observation 加载失败: id={}, error={}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String id) {
        try {
            String filePath = storePath + File.separator + id + ".json";
            File file = new File(filePath);

            if (file.exists()) {
                file.delete();
                LOG.debug("Observation 已删除: id={}", id);
            }

        } catch (Throwable e) {
            LOG.warn("Observation 删除失败: id={}, error={}", id, e.getMessage());
        }
    }

    @Override
    public List<Observation> loadAll() {
        try {
            File dir = new File(storePath);

            if (!dir.exists()) {
                return new ArrayList<>();
            }

            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

            if (files == null) {
                return new ArrayList<>();
            }

            List<Observation> observations = new ArrayList<>();

            for (File file : files) {
                try {
                    String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    Observation obs = ONode.deserialize(json, Observation.class);

                    // 跳过已过期的观察
                    if (obs != null && obs.getDecayedImportance() > 0) {
                        observations.add(obs);
                    }

                } catch (Throwable e) {
                    LOG.debug("加载 Observation 文件失败: file={}, error={}", file.getName(), e.getMessage());
                }
            }

            LOG.info("加载 Observation: count={}", observations.size());
            return observations;

        } catch (Throwable e) {
            LOG.warn("加载 Observation 失败: error={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void clear() {
        try {
            File dir = new File(storePath);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

            if (files != null) {
                int count = 0;
                for (File file : files) {
                    if (file.delete()) {
                        count++;
                    }
                }
                LOG.info("清空 Observation: count={}", count);
            }

        } catch (Throwable e) {
            LOG.warn("清空 Observation 失败: error={}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            File dir = new File(storePath);
            File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

            if (files != null) {
                stats.put("count", files.length);

                // 计算总大小
                long totalSize = 0;
                for (File file : files) {
                    totalSize += file.length();
                }
                stats.put("totalBytes", totalSize);
                stats.put("totalSize", formatBytes(totalSize));
            }

        } catch (Throwable e) {
            LOG.warn("获取统计信息失败: error={}", e.getMessage());
        }

        return stats;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        }
    }
}
