package dev.tachyonmcp.docs.annotations;

// snips-start: annotations_custom_provider
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.lang.reflect.Method;

public class MyFrameworkAnnotationProvider implements AnnotationProvider {
    @Override
    public void register(Object instance, AnnotationRegistrationContext context) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            MyTool tool = method.getAnnotation(MyTool.class);
            if (tool == null) continue;
            context.tools().register(
                ToolDescriptor.builder().name(tool.name()).build(),
                (ctx, req) -> ToolResult.text(method.invoke(instance).toString()));
        }
    }
}
// snips-end: annotations_custom_provider
