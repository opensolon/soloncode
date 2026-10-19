package org.noear.solon.codecli.artifact;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublishedImageTest {
    @Test
    void immutableSnapshotCopiesBothInputAndOutput() {
        byte[] bytes = {1, 2, 3};
        PublishedImage image = new PublishedImage(bytes, "image.png", "说明");
        bytes[0] = 9;
        image.getBytes()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, image.getBytes());
        assertEquals("image.png", image.getFilename());
        assertEquals("说明", image.getCaption());
        assertEquals(image.getUuid(), UUID.fromString(image.getUuid()).toString());
        assertNotEquals(image.getUuid(), new PublishedImage(bytes, "image.png", null).getUuid());
        assertTrue(Modifier.isFinal(PublishedImage.class.getModifiers()));
        java.util.Arrays.stream(PublishedImage.class.getDeclaredFields())
                .forEach(field -> assertTrue(Modifier.isFinal(field.getModifiers())));
    }
}
