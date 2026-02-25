package org.noear.solon.ai.codecli.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道注册表（插件化架构核心）
 * 实现动态通道注册、发现和管理
 */
public class ChannelRegistry {
    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);
    
    private final Map<String, BotChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, ChannelDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, ChannelFactory> factories = new ConcurrentHashMap<>();
    
    /**
     * 注册通道描述符
     */
    public void registerDescriptor(ChannelDescriptor descriptor) {
        descriptors.put(descriptor.getType(), descriptor);
        log.info("Registered channel descriptor: {}", descriptor.getType());
    }
    
    /**
     * 注册通道工厂
     */
    public void registerFactory(String channelType, ChannelFactory factory) {
        factories.put(channelType, factory);
        log.info("Registered channel factory for: {}", channelType);
    }
    
    /**
     * 创建并注册通道实例
     */
    public BotChannel createChannel(String channelType, String channelId, Map<String, Object> config) {
        ChannelFactory factory = factories.get(channelType);
        if (factory == null) {
            throw new IllegalArgumentException("No factory found for channel type: " + channelType);
        }
        
        BotChannel channel = factory.create(channelId, config);
        registerChannel(channel);
        return channel;
    }
    
    /**
     * 注册通道实例
     */
    public void registerChannel(BotChannel channel) {
        channels.put(channel.getChannelId(), channel);
        log.info("Registered channel: {}", channel.getChannelId());
    }
    
    /**
     * 获取通道
     */
    public BotChannel getChannel(String channelId) {
        return channels.get(channelId);
    }
    
    /**
     * 获取所有通道
     */
    public Collection<BotChannel> getAllChannels() {
        return new ArrayList<>(channels.values());
    }
    
    /**
     * 获取通道描述符
     */
    public ChannelDescriptor getDescriptor(String channelType) {
        return descriptors.get(channelType);
    }
    
    /**
     * 获取所有描述符
     */
    public Collection<ChannelDescriptor> getAllDescriptors() {
        return new ArrayList<>(descriptors.values());
    }
    
    /**
     * 启动所有通道
     */
    public void startAll() {
        channels.values().forEach(channel -> {
            try {
                channel.start();
                log.info("Started channel: {}", channel.getChannelId());
            } catch (Exception e) {
                log.error("Failed to start channel: {}", channel.getChannelId(), e);
            }
        });
    }
    
    /**
     * 停止所有通道
     */
    public void stopAll() {
        channels.values().forEach(channel -> {
            try {
                channel.stop();
                log.info("Stopped channel: {}", channel.getChannelId());
            } catch (Exception e) {
                log.error("Failed to stop channel: {}", channel.getChannelId(), e);
            }
        });
    }
    
    /**
     * 健康检查所有通道
     */
    public Map<String, BotChannel.HealthStatus> healthCheckAll() {
        Map<String, BotChannel.HealthStatus> results = new HashMap<>();
        channels.forEach((id, channel) -> {
            try {
                results.put(id, channel.healthCheck());
            } catch (Exception e) {
                results.put(id, new BotChannel.HealthStatus(false, "Health check failed: " + e.getMessage()));
            }
        });
        return results;
    }
    
    /**
     * 移除通道
     */
    public void removeChannel(String channelId) {
        BotChannel channel = channels.remove(channelId);
        if (channel != null) {
            try {
                channel.stop();
                log.info("Removed channel: {}", channelId);
            } catch (Exception e) {
                log.error("Failed to stop removed channel: {}", channelId, e);
            }
        }
    }
    
    /**
     * 通道描述符
     */
    public static class ChannelDescriptor {
        private final String type;
        private final String name;
        private final String description;
        private final List<String> supportedFeatures;
        private final Map<String, String> configSchema;
        
        public ChannelDescriptor(String type, String name, String description) {
            this.type = type;
            this.name = name;
            this.description = description;
            this.supportedFeatures = new ArrayList<>();
            this.configSchema = new HashMap<>();
        }
        
        public ChannelDescriptor addFeature(String feature) {
            supportedFeatures.add(feature);
            return this;
        }
        
        public ChannelDescriptor addConfigField(String key, String type) {
            configSchema.put(key, type);
            return this;
        }
        
        // Getters
        public String getType() { return type; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<String> getSupportedFeatures() { return supportedFeatures; }
        public Map<String, String> getConfigSchema() { return configSchema; }
    }
    
    /**
     * 通道工厂接口
     */
    @FunctionalInterface
    public interface ChannelFactory {
        BotChannel create(String channelId, Map<String, Object> config);
    }
    
    /**
     * 内置通道类型常量
     */
    public static class ChannelTypes {
        public static final String WEB = "web";
        public static final String TELEGRAM = "telegram";
        public static final String SLACK = "slack";
        public static final String DISCORD = "discord";
        public static final String WHATSAPP = "whatsapp";
    }
}