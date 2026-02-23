package demo.ai.cli;

import org.noear.solon.ai.codecli.core.CodeAgent;
import org.noear.solon.ai.codecli.portal.BotGate;
import org.noear.solon.ai.codecli.portal.ChannelRegistry;
import org.noear.solon.ai.codecli.portal.WebChannel;

import java.util.HashMap;
import java.util.Map;

/**
 * BotGate 使用示例
 * 演示如何创建和配置 BotGate 交互网关
 */
public class BotGateExample {
    
    public static void main(String[] args) {
        // 注意：实际使用时需要提供真实的 ChatModel
        // ChatModel chatModel = ...;
        // CodeAgent codeAgent = new CodeAgent(chatModel);
        
        // 为了演示，我们使用 null（实际运行时会报错）
        CodeAgent codeAgent = null;
        
        // 创建 BotGate 实例
        BotGate botGate = new BotGate(codeAgent);
        
        // 注册通道工厂
        botGate.registerChannelFactory(ChannelRegistry.ChannelTypes.WEB,
            (id, config) -> {
                Integer port = (Integer) config.getOrDefault("port", 8080);
                return new WebChannel(id, port, codeAgent);
            });
        
        // 创建 Web 通道
        Map<String, Object> webConfig = new HashMap<>();
        webConfig.put("port", 8080);
        botGate.createChannel("web", "main", webConfig);
        
        // 启动所有通道
        botGate.startAll();
        
        System.out.println("BotGate started successfully!");
        System.out.println("Web channel available at: http://localhost:8080");
    }
}