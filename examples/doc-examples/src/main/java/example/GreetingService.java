// snips-start: springboot_service
package example;

import dev.tachyonmcp.api.annotations.McpTool;
import org.springframework.stereotype.Component;

@Component
public class GreetingService {
    @McpTool(description = "Say hello to someone")
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
// snips-end: springboot_service
