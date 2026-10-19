package org.noear.solon.codecli;

import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.api.web.controller.InstanceWebController;
import org.noear.solon.codecli.util.WebInstanceRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguratorWebInstanceTest {
    @Test
    void onlyReadyInstancePublishesItsMemoryToken() {
        int port = 54000 + (int) Math.floorMod(System.nanoTime(), 1000L);
        String running = WebInstanceRegistry.register(port);
        String pending = WebInstanceRegistry.generateToken();
        InstanceWebController controller = new InstanceWebController(null, pending);
        try {
            assertEquals(false, controller.info().getData().get("attachSupported"));
            assertEquals(running, WebInstanceRegistry.readToken(port));
            // 尚未收到启动成功事件的实例，即使退出也不能删除正在运行实例的记录。
            WebInstanceRegistry.remove(port, pending);
            assertEquals(running, WebInstanceRegistry.readToken(port));

            Configurator.publishWebInstance(port, pending, controller);
            assertEquals(pending, WebInstanceRegistry.readToken(port));
            assertEquals(true, controller.info().getData().get("attachSupported"));
            WebInstanceRegistry.remove(port, running);
            assertEquals(pending, WebInstanceRegistry.readToken(port));
            WebInstanceRegistry.remove(port, pending);
            assertNull(WebInstanceRegistry.readToken(port));
        } finally {
            WebInstanceRegistry.remove(port, pending);
            WebInstanceRegistry.remove(port, running);
        }
    }

    @Test
    void publicationFailureDoesNotAdvertiseAttachSupport() throws Exception {
        int port = 55000 + (int) Math.floorMod(System.nanoTime(), 1000L);
        String token = WebInstanceRegistry.generateToken();
        InstanceWebController controller = new InstanceWebController(null, token);
        Path file = WebInstanceRegistry.fileForPort(port);
        // 非空目录不能被注册文件覆盖，确定性模拟发布失败。
        Files.createDirectories(file);
        Path blocker = Files.createFile(file.resolve("blocker"));
        try {
            assertDoesNotThrow(() -> Configurator.publishWebInstance(port, token, controller));
            assertEquals(false, controller.info().getData().get("attachSupported"));
            assertNull(WebInstanceRegistry.readToken(port));
        } finally {
            Files.deleteIfExists(blocker);
            Files.deleteIfExists(file);
        }
    }
}
