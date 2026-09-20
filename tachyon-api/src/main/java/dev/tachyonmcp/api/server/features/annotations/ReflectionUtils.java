/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.annotations;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Policy-free reflection queries behind {@link AnnotationInvocationSupport#discoverMethods}: which
 * interfaces a class exposes and whether one method overrides another. Public so Tachyon's own
 * modules and annotation providers can share it; not a stability contract.
 */
@InternalApi
public final class ReflectionUtils {

    private ReflectionUtils() {}

    /**
     * Returns whether {@code method} is a declared method, neither synthetic nor a compiler
     * bridge, carrying at least one of {@code annotationTypes}.
     *
     * @param method          the method to test
     * @param annotationTypes the annotation types to look for
     * @return {@code true} if the method is a real declaration carrying one of the annotations
     */
    public static boolean isAnnotatedDeclaration(
            Method method, Collection<Class<? extends Annotation>> annotationTypes) {
        if (method.isSynthetic() || method.isBridge()) return false;
        for (final Class<? extends Annotation> annotationType : annotationTypes) {
            if (method.isAnnotationPresent(annotationType)) return true;
        }
        return false;
    }

    /**
     * Returns every interface reachable from {@code clazz} or any of its superclasses,
     * breadth-first.
     *
     * @param clazz the class whose interfaces to collect
     * @return the reachable interfaces, nearest first
     */
    public static Set<Class<?>> interfacesOf(Class<?> clazz) {
        final Set<Class<?>> found = new LinkedHashSet<>();
        final Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Collections.addAll(pending, c.getInterfaces());
        }
        while (!pending.isEmpty()) {
            final Class<?> iface = pending.poll();
            if (found.add(iface)) Collections.addAll(pending, iface.getInterfaces());
        }
        return found;
    }

    /**
     * Returns whether some {@code candidates} method overrides {@code declaration} as {@code
     * scanned} sees it: same name and the same erased parameter types once type variables are
     * resolved through {@code scanned}'s generic supertypes (JLS 8.4.2). Assignability is not
     * enough, since {@code run(String)} overloads {@code run(Object)} rather than overriding it.
     * Compiler-emitted bridge methods are not consulted, so the answer does not depend on the class
     * file carrying them.
     *
     * @param scanned     the class whose hierarchy supplies the type arguments
     * @param declaration the method that may be overridden
     * @param candidates  the methods that may override it
     * @return {@code true} if a candidate has {@code declaration}'s name and resolved signature
     */
    public static boolean isOverriddenBy(Class<?> scanned, Method declaration, Collection<Method> candidates) {
        final Map<TypeVariable<?>, Type> bindings = typeBindings(scanned);
        final Class<?>[] declared = erasedParameterTypes(declaration, bindings);
        for (final Method candidate : candidates) {
            if (candidate.getName().equals(declaration.getName())
                    && candidate.getParameterCount() == declaration.getParameterCount()
                    && Arrays.equals(erasedParameterTypes(candidate, bindings), declared)) return true;
        }
        return false;
    }

    /**
     * Returns a function that substitutes the type variables {@code scanned}'s generic supertypes
     * bind, so a declaration read off a generic interface or superclass can be typed as {@code
     * scanned} sees it: {@code T} of {@code Operation<T>} becomes {@code String} for a class that
     * implements {@code Operation<String>}. Variables are replaced at the top level, as type
     * arguments and as array components; wildcard bounds are left as declared, and a variable
     * {@code scanned} leaves unbound stays a variable.
     *
     * @param scanned the class whose hierarchy supplies the type arguments
     * @return a resolver from a declared type to its type as {@code scanned} sees it
     */
    public static UnaryOperator<Type> resolverFor(Class<?> scanned) {
        final Map<TypeVariable<?>, Type> bindings = typeBindings(scanned);
        return type -> resolve(type, bindings);
    }

    /**
     * Returns the erasure of {@code type}: the class a value of that type is an instance of. An
     * unbound type variable erases to its first bound.
     *
     * @param type the type to erase
     * @return the erased class
     */
    public static Class<?> erase(Type type) {
        return erase(type, Map.of());
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case TypeVariable<?> variable -> {
                final Type bound = bindings.get(variable);
                yield bound == null ? variable : resolve(bound, bindings);
            }
            case ParameterizedType parameterized -> resolveArguments(parameterized, bindings);
            case GenericArrayType array -> {
                final Type component = resolve(array.getGenericComponentType(), bindings);
                yield component instanceof Class<?> cls
                        ? Array.newInstance(cls, 0).getClass()
                        : array;
            }
            default -> type;
        };
    }

    private static ParameterizedType resolveArguments(
            ParameterizedType parameterized, Map<TypeVariable<?>, Type> bindings) {
        final Type[] declared = parameterized.getActualTypeArguments();
        final Type[] resolved = new Type[declared.length];
        boolean changed = false;
        for (int i = 0; i < declared.length; i++) {
            resolved[i] = resolve(declared[i], bindings);
            changed |= resolved[i] != declared[i];
        }
        return changed
                ? new ResolvedParameterizedType(parameterized.getRawType(), parameterized.getOwnerType(), resolved)
                : parameterized;
    }

    /** A {@link ParameterizedType} whose arguments were substituted; equal to any JDK type with the same parts. */
    private static final class ResolvedParameterizedType implements ParameterizedType {
        private final Type raw;
        private final @Nullable Type owner;
        private final Type[] arguments;

        ResolvedParameterizedType(Type raw, @Nullable Type owner, Type[] arguments) {
            this.raw = raw;
            this.owner = owner;
            this.arguments = arguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public @Nullable Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType that
                    && raw.equals(that.getRawType())
                    && Objects.equals(owner, that.getOwnerType())
                    && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            return raw.getTypeName()
                    + Arrays.stream(arguments).map(Type::getTypeName).collect(Collectors.joining(", ", "<", ">"));
        }
    }

    /**
     * Maps every type variable of a generic supertype of {@code clazz} to the type argument the
     * hierarchy supplies for it. Chains such as {@code Leaf extends Mid<String>}, {@code Mid<U>
     * extends Base<U>} resolve transitively in {@link #erase}.
     */
    private static Map<TypeVariable<?>, Type> typeBindings(Class<?> clazz) {
        final Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        collectBindings(clazz, bindings);
        return bindings;
    }

    private static void collectBindings(Type type, Map<TypeVariable<?>, Type> bindings) {
        switch (type) {
            case ParameterizedType parameterized -> {
                final Class<?> raw = (Class<?>) parameterized.getRawType();
                final TypeVariable<?>[] variables = raw.getTypeParameters();
                final Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++) bindings.put(variables[i], arguments[i]);
                collectBindings(raw, bindings);
            }
            case Class<?> cls -> {
                final Type superclass = cls.getGenericSuperclass();
                if (superclass != null) collectBindings(superclass, bindings);
                for (final Type iface : cls.getGenericInterfaces()) collectBindings(iface, bindings);
            }
            default -> {}
        }
    }

    private static Class<?>[] erasedParameterTypes(Method method, Map<TypeVariable<?>, Type> bindings) {
        final Type[] generic = method.getGenericParameterTypes();
        final Class<?>[] erased = new Class<?>[generic.length];
        for (int i = 0; i < generic.length; i++) erased[i] = erase(generic[i], bindings);
        return erased;
    }

    /** Erasure of {@code type} after substituting {@code bindings}; an unbound variable erases to its first bound. */
    private static Class<?> erase(Type type, Map<TypeVariable<?>, Type> bindings) {
        return switch (type) {
            case Class<?> cls -> cls;
            case ParameterizedType parameterized -> erase(parameterized.getRawType(), bindings);
            case GenericArrayType array ->
                Array.newInstance(erase(array.getGenericComponentType(), bindings), 0)
                        .getClass();
            case TypeVariable<?> variable -> {
                final Type bound = bindings.get(variable);
                yield erase(bound != null ? bound : variable.getBounds()[0], bindings);
            }
            case WildcardType wildcard -> erase(wildcard.getUpperBounds()[0], bindings);
            default -> Object.class;
        };
    }
}
