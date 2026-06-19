package labs.bot.codecli;

import org.noear.solon.codecli.config.entity.ModelDo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ModelDoTest {
    @Test
    public void testModelDoCreation() {
        ModelDo model = new ModelDo();
        model.setName("test-name");
        model.setModel("test-model");
        model.setStandard("openai");
        model.setApiUrl("https://api.test.com");
        model.setApiKey("test-key");
        model.setScope("global");
        
        assertEquals("test-name", model.getNameOrModel());
        assertEquals("test-model", model.getModel());
        assertEquals("openai", model.getStandardOrProvider());
        assertEquals("https://api.test.com", model.getApiUrl());
        assertEquals("test-key", model.getApiKey());
        assertEquals("global", model.getScope());
        
        System.out.println("ModelDo creation test passed");
    }
}