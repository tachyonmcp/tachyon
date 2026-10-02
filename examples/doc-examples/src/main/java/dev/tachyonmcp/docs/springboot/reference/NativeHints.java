package dev.tachyonmcp.docs.springboot.reference;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

// snips-start: springboot_native_hints
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({GreetingRequest.class, GreetingResponse.class})
class NativeHints {}
// snips-end: springboot_native_hints
