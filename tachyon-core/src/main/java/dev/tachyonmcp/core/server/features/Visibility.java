/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Whether the caller of a request may see a tool, resource, resource template or prompt. The one check
 * every path asks, so listing and access cannot disagree: the feature's extension must be negotiated for
 * the request, and the server's {@link dev.tachyonmcp.core.server.security.AccessPolicy} must permit it
 * to the request's caller.
 *
 * <p>A feature that is not visible is answered exactly like one that does not exist, by every caller of
 * this class, so the answer is no oracle for what is hidden.
 */
@InternalApi
public final class Visibility {

    private Visibility() {}

    /**
     * Returns whether {@code context}'s caller may see {@code feature}.
     *
     * @param context the request being served
     * @param feature a tool, resource, resource template or prompt descriptor
     * @return {@code true} if it is listed for and usable by the caller
     * @throws IllegalArgumentException for any other kind of descriptor: nothing is guessed about it
     */
    public static boolean of(DispatchContext context, ServerFeature.Descriptor feature) {
        final var extensionId = extensionId(feature);
        if (extensionId != null && !context.isExtensionEnabled(extensionId)) {
            return false;
        }
        return context.accessPolicy().permits(context.securityContext(), feature);
    }

    /** The filter a listing applies: {@link #of} for this request, in the shape the registries take. */
    public static <D extends ServerFeature.Descriptor> Predicate<D> to(DispatchContext context) {
        return feature -> of(context, feature);
    }

    private static @Nullable String extensionId(ServerFeature.Descriptor feature) {
        return switch (feature) {
            case ToolDescriptor tool -> tool.extensionId();
            case ResourceDescriptor resource -> resource.extensionId();
            case ResourceTemplateDescriptor template -> template.extensionId();
            case PromptDescriptor prompt -> prompt.extensionId();
            default ->
                throw new IllegalArgumentException(
                        "Not a feature a caller can see: " + feature.getClass().getName());
        };
    }
}
