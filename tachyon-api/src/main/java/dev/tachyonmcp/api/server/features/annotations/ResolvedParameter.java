/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.annotations;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;

/**
 * A parameter of a discovered method, typed as the scanned class sees it. {@link
 * AnnotationInvocationSupport#discoverMethods} may return a declaration from a generic interface or
 * superclass, whose parameter type is then a bare type variable; {@link #type} is that type with the
 * variable replaced by what the scanned class binds it to.
 *
 * @param parameter the reflected parameter, the source of its name and annotations
 * @param type      the parameter's generic type with type variables resolved against the scanned class
 */
@ExperimentalApi
public record ResolvedParameter(Parameter parameter, Type type) {

    /**
     * Returns the erasure of {@link #type}.
     *
     * @return the class a value bound to this parameter is an instance of
     */
    public Class<?> rawType() {
        return ReflectionUtils.erase(type);
    }
}
