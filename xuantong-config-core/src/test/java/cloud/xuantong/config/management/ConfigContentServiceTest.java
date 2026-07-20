package cloud.xuantong.config.management;

import cloud.xuantong.config.management.content.ConfigContentResult;
import cloud.xuantong.config.management.content.ConfigContentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigContentServiceTest {
    private final ConfigContentService service = new ConfigContentService();

    @Test
    void validatesAndFormatsStrictJson() {
        assertTrue(service.validate("json", "{\"name\":\"xuantong\",\"count\":2}").valid());
        assertFalse(service.validate("json", "{'name':'xuantong'}").valid());
        assertFalse(service.validate("json", "{name:1}").valid());

        ConfigContentResult formatted = service.format(
                "json", "{\"name\":\"xuantong\",\"items\":[1,2]}");
        assertTrue(formatted.valid());
        assertTrue(formatted.content().contains("\n"));
        assertEquals("{\"name\":\"xuantong\",\"items\":[1,2]}",
                service.minify("json", formatted.content()).content());
    }

    @Test
    void rejectsDuplicateYamlKeysAndUnsafeTypes() {
        assertFalse(service.validate("yaml", "server:\n  port: 8080\n  port: 9090\n").valid());
        assertFalse(service.validate("yaml", "value: !!java.lang.Runtime {}\n").valid());
        ConfigContentResult formatted = service.format("yaml", "server: {port: 8080}\n");
        assertTrue(formatted.valid());
        assertTrue(formatted.content().contains("port: 8080"));
    }

    @Test
    void securesAndFormatsXml() {
        assertTrue(service.validate("xml", "<server><port>8080</port></server>").valid());
        assertFalse(service.validate("xml", "<server><port></server>").valid());
        assertFalse(service.validate("xml", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>").valid());
        assertTrue(service.format("xml", "<server><port>8080</port></server>")
                .content().contains("\n"));
    }

    @Test
    void validatesPropertiesDuplicatesEscapesAndFormatting() {
        assertTrue(service.validate("properties", "server.port=8080\nname=xuantong\n").valid());
        ConfigContentResult duplicate = service.validate(
                "properties", "server.port=8080\nserver.port=9090\n");
        assertFalse(duplicate.valid());
        assertEquals(2, duplicate.issues().getFirst().line());
        assertFalse(service.validate("properties", "bad=\\q\n").valid());
        assertFalse(service.validate("properties", "bad=\\u12xz\n").valid());
        assertEquals("a = 1\nz = 2\n",
                service.format("properties", "z=2\na=1\n").content());
    }

    @Test
    void validatesTypedScalarValuesAndSizeLimit() {
        assertTrue(service.validate("string", "hello").valid());
        assertTrue(service.validate("number", "10.50").valid());
        assertEquals("10.5", service.format("number", "10.50").content());
        assertFalse(service.validate("number", "NaN").valid());
        assertTrue(service.validate("boolean", "TRUE").valid());
        assertEquals("true", service.format("boolean", "TRUE").content());
        assertFalse(service.validate("boolean", "yes").valid());
        assertFalse(service.validate("text", "x".repeat(ConfigContentService.MAX_CONTENT_BYTES + 1)).valid());
    }
}
