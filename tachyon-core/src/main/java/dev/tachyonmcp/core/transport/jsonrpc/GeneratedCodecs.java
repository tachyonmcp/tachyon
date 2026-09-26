/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.jsonrpc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;

/**
 * Encodes ts2java-generated models (core or extension) with their generated codec. By the
 * generator's layout, a model {@code <pkg>.models.X} (or a nested {@code X.Inner}) is served by
 * {@code <pkg>.codecs.CodecRegistry#codecFor}; an extension registry falls back to its base
 * package's. The registry is consulted on every call, so runtime overrides apply.
 */
final class GeneratedCodecs {

    private static final String MODELS_SUFFIX = ".models";

    /** {@code CodecRegistry#codecFor} bound to the model class, or {@code null} for non-models. */
    private static final ClassValue<@Nullable MethodHandle> CODEC_LOOKUPS = new ClassValue<>() {
        @Override
        protected @Nullable MethodHandle computeValue(Class<?> type) {
            return codecLookup(type);
        }
    };

    /** The codec's {@code encode(JsonGenerator, Object)}, keyed by codec class. */
    private static final ClassValue<MethodHandle> ENCODERS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> codecType) {
            try {
                return MethodHandles.publicLookup()
                        .findVirtual(
                                codecType,
                                "encode",
                                MethodType.methodType(void.class, JsonGenerator.class, Object.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Not a generated codec: " + codecType.getName(), e);
            }
        }
    };

    private GeneratedCodecs() {}

    /**
     * Writes {@code value} with its generated codec.
     *
     * @return {@code false} when {@code value} is not a generated model with a registered codec
     */
    static boolean encode(JsonGenerator gen, Object value) {
        var lookup = CODEC_LOOKUPS.get(value.getClass());
        if (lookup == null) {
            return false;
        }
        try {
            var codec = (Object) lookup.invoke();
            if (codec == null) {
                return false;
            }
            ENCODERS.get(codec.getClass()).invoke(codec, gen, value);
            return true;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private static @Nullable MethodHandle codecLookup(Class<?> type) {
        var pkg = type.getPackageName();
        if (!pkg.endsWith(MODELS_SUFFIX)) {
            return null;
        }
        var registryName = pkg.substring(0, pkg.length() - MODELS_SUFFIX.length()) + ".codecs.CodecRegistry";
        try {
            var registry = Class.forName(registryName, true, type.getClassLoader());
            var codecFor = MethodHandles.publicLookup().unreflect(registry.getMethod("codecFor", Class.class));
            return MethodHandles.insertArguments(codecFor, 0, type);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Inaccessible codec registry " + registryName, e);
        }
    }
}
