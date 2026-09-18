// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.api.annotations.ExperimentalApi
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.Annotations
import dev.tachyonmcp.api.server.domain.Icon
import dev.tachyonmcp.api.server.domain.PromptArgument
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.domain.ResourceContents
import dev.tachyonmcp.api.server.domain.ToolAnnotations
import dev.tachyonmcp.api.server.extensions.ServerExtension
import dev.tachyonmcp.api.server.features.completions.CompletionResult
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.core.server.ServerBuilder
import dev.tachyonmcp.core.server.config.NetworkConfig
import dev.tachyonmcp.core.server.features.resources.MimeTypes
import dev.tachyonmcp.kotlin.server.DefaultKotlinTachyonServer
import dev.tachyonmcp.kotlin.server.TachyonDsl
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.features.CoroutineRuntime
import dev.tachyonmcp.kotlin.server.features.tools.toolDescriptorOf
import dev.tachyonmcp.kotlin.server.json.toJsonSchema
import dev.tachyonmcp.kotlin.server.json.toJsonSchemaOrNull
import io.netty.channel.ChannelPipeline
import kotlinx.serialization.json.JsonObject
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import dev.tachyonmcp.core.server.TachyonServer as CoreTachyonServer

@TachyonDsl
public class TachyonServerBuilder
    @PublishedApi
    internal constructor() {
        @PublishedApi
        internal val delegate: ServerBuilder = CoreTachyonServer.builder()

        @PublishedApi
        internal var networkPortExplicitlySet: Boolean = false

        private val coroutineRuntime: CoroutineRuntime =
            CoroutineRuntime().also { delegate.withExtensions(it) }

        private val featureRegistrar: KotlinFeatureRegistrar =
            KotlinFeatureRegistrar(delegate, coroutineRuntime)

        @OptIn(ExperimentalContracts::class)
        public inline fun info(
            crossinline configure: (@TachyonDsl ServerInfoScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = ServerInfoScope()
            scope.configure()
            delegate.info { scope.applyTo(it) }
            return this
        }

        @OptIn(ExperimentalContracts::class)
        public inline fun capabilities(
            crossinline configure: (@TachyonDsl CapabilitiesScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = CapabilitiesScope()
            scope.configure()
            delegate.capabilities { scope.applyTo(it) }
            return this
        }

        @OptIn(ExperimentalContracts::class)
        public inline fun network(
            crossinline configure: (@TachyonDsl NetworkScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = NetworkScope()
            scope.configure()
            delegate.network { scope.applyTo(it) }
            if (scope.port != null) {
                networkPortExplicitlySet = true
            }
            return this
        }

        @OptIn(ExperimentalContracts::class)
        public inline fun session(
            crossinline configure: (@TachyonDsl SessionScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = SessionScope()
            scope.configure()
            delegate.session { scope.applyTo(it) }
            return this
        }

        @OptIn(ExperimentalContracts::class)
        public inline fun runtime(
            crossinline configure: (@TachyonDsl RuntimeScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = RuntimeScope()
            scope.configure()
            delegate.runtime { scope.applyTo(it) }
            return this
        }

        /**
         * Configures slow-request diagnostics and the passive MCP observation lifecycle.
         */
        @OptIn(ExperimentalContracts::class)
        @ExperimentalApi
        public inline fun observability(
            crossinline configure: (@TachyonDsl ObservabilityScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            val scope = ObservabilityScope()
            scope.configure()
            delegate.observability { scope.applyTo(it) }
            return this
        }

        /**
         * Registers a tool, accepting every optional attribute of [ToolDescriptor.Builder]; pass
         * a prebuilt [ToolDescriptor] instead when a descriptor is already at hand.
         */
        @JvmSynthetic
        @Suppress("LongParameterList")
        public fun tool(
            name: String,
            description: String? = null,
            title: String? = null,
            inputSchema: JsonSchema? = null,
            outputSchema: JsonSchema? = null,
            taskSupport: TaskSupport? = null,
            annotations: ToolAnnotations? = null,
            icons: List<Icon>? = null,
            meta: Map<String, Any>? = null,
            block: suspend ToolScope.() -> ToolResult,
        ): TachyonServerBuilder =
            tool(
                descriptor =
                    toolDescriptorOf(
                        name = name,
                        description = description,
                        title = title,
                        inputSchema = inputSchema,
                        outputSchema = outputSchema,
                        taskSupport = taskSupport,
                        annotations = annotations,
                        icons = icons,
                        meta = meta,
                    ),
                block = block,
            )

        @JvmSynthetic
        @Suppress("MaxLineLength")
        @Deprecated(
            level = DeprecationLevel.WARNING,
            message = "Use tool(...) with explicit JsonSchema object",
            replaceWith =
                ReplaceWith(
                    "tool(name = name, description = description, inputSchema = JsonSchema.parse(inputSchema), outputSchema = outputSchema?.let(JsonSchema::parse), taskSupport = taskSupport, block = block)",
                    "dev.tachyonmcp.api.json.JsonSchema",
                ),
        )
        public fun tool(
            name: String,
            description: String? = null,
            inputSchema: String,
            outputSchema: String? = null,
            taskSupport: TaskSupport? = null,
            block: suspend ToolScope.() -> ToolResult,
        ): TachyonServerBuilder =
            this.also {
                featureRegistrar.tool(
                    name,
                    description,
                    inputSchema,
                    outputSchema,
                    taskSupport,
                    block,
                )
            }

        /**
         * Registers a tool whose input/output schemas are resolved from [In]/[Out] via
         * [schemaGenerator], which defaults to
         * [dev.tachyonmcp.api.json.JsonSchema.generate] — the service-loaded
         * [dev.tachyonmcp.api.json.spi.JsonSchemaFactory] chain in ascending priority order: a
         * build-time codegen resource (tachyon-core's `KtSchemaResourceFactory`) wins when
         * present, otherwise the runtime reflection generator in the `tachyon-kotlin-kt-schema`
         * integration artifact (its `KtSchemaReflectionFactory`, registered via
         * `META-INF/services`) back-fills. Add that artifact to the classpath to use `typedTool`
         * without a codegen resource.
         *
         * Pass [schemaGenerator] to control generation for this call only.
         *
         * The call arguments are decoded into [In] by the configured serde, and the block may
         * return **either** shape:
         *  - an [Out] — wrapped into a success result carrying it as `structuredContent`;
         *  - a [ToolResult] — passed through untouched, for results that also need `_meta`, a
         *    custom text block, extra content blocks, [ToolScope.fail] or
         *    [ToolScope.inputRequired].
         *
         * The two never collide: [ToolResult] is a sealed interface, so no [Out] can also be
         * one. A value that is neither fails with [ClassCastException] naming the expected type.
         *
         * Both type arguments must be given explicitly — neither is inferable from the block.
         * The name stays `typedTool` for symmetry with the rest of the build-time DSL; the
         * original reason (a reified `tool` overload hijacking schema-less `tool(name) { }`
         * calls) no longer applies, since an explicit type argument list excludes every untyped
         * overload.
         */
        @ExperimentalApi
        @JvmSynthetic
        @Suppress("LongParameterList")
        public inline fun <reified In : Any, reified Out : Any> typedTool(
            name: String,
            description: String? = null,
            title: String? = null,
            taskSupport: TaskSupport? = null,
            noinline schemaGenerator: (Class<*>) -> JsonSchema = JsonSchema::generate,
            annotations: ToolAnnotations? = null,
            icons: List<Icon>? = null,
            meta: Map<String, Any>? = null,
            noinline block: suspend ToolScope.(In) -> Any,
        ): TachyonServerBuilder {
            val inputType = In::class.java
            val outputType = Out::class.java
            val descriptor =
                toolDescriptorOf(
                    name = name,
                    description = description,
                    title = title,
                    inputSchema = schemaGenerator(inputType),
                    outputSchema = schemaGenerator(outputType),
                    taskSupport = taskSupport,
                    annotations = annotations,
                    icons = icons,
                    meta = meta,
                )
            return tool(descriptor) {
                when (val produced = block(arguments.decode(inputType))) {
                    is ToolResult -> produced
                    else -> success(outputType.cast(produced))
                }
            }
        }

        /**
         * Registers a prebuilt tool descriptor with a suspending handler block.
         *
         * @param descriptor tool descriptor
         * @param block handler invoked for tool calls
         * @return this builder
         */
        @JvmSynthetic
        public fun tool(
            descriptor: ToolDescriptor,
            block: suspend ToolScope.() -> ToolResult,
        ): TachyonServerBuilder = this.also { featureRegistrar.tool(descriptor, block) }

        /**
         * Registers a static resource with a suspending handler block.
         *
         * @param name resource name
         * @param uri resource URI
         * @param description optional resource description
         * @param mimeType resource MIME type; defaults to a guess from `uri`'s extension
         * @param title optional human-readable title
         * @param annotations optional presentation hints
         * @param size optional raw content size in bytes
         * @param icons associated icons, or an empty list
         * @param meta optional protocol extension metadata
         * @param block handles reads of the registered resource
         * @return this builder
         */
        @JvmSynthetic
        @Suppress("LongParameterList")
        public fun resource(
            name: String,
            uri: String,
            description: String? = null,
            mimeType: String? = MimeTypes.guess(uri),
            title: String? = null,
            annotations: Annotations? = null,
            size: Long? = null,
            icons: List<Icon> = emptyList(),
            meta: Map<String, Any>? = null,
            block: suspend ResourceScope.() -> ResourceContents,
        ): TachyonServerBuilder =
            resource(
                descriptor =
                    ResourceDescriptor
                        .builder()
                        .name(name)
                        .uri(uri)
                        .description(description)
                        .mimeType(mimeType)
                        .title(title)
                        .annotations(annotations)
                        .size(size)
                        .icons(icons)
                        .meta(meta)
                        .build(),
                block = block,
            )

        /**
         * Registers a prebuilt static-resource descriptor.
         *
         * @param descriptor static-resource descriptor
         * @param block handler invoked for resource reads
         * @return this builder
         */
        @JvmSynthetic
        public fun resource(
            descriptor: ResourceDescriptor,
            block: suspend ResourceScope.() -> ResourceContents,
        ): TachyonServerBuilder = this.also { featureRegistrar.resource(descriptor, block) }

        /**
         * Registers a prompt with the server.
         *
         * @param name The prompt name.
         * @param description An optional description of the prompt.
         * @param title The optional human-readable title.
         * @param arguments The arguments accepted by this prompt, or an empty list.
         * @param inputSchema The optional JSON schema describing the prompt's arguments.
         * @param icons The prompt icons, or an empty list.
         * @param meta The optional protocol extension metadata.
         * @param block The handler that generates the prompt messages.
         * @return This builder.
         */
        @JvmSynthetic
        @Suppress("LongParameterList")
        public fun prompt(
            name: String,
            description: String? = null,
            title: String? = null,
            arguments: List<PromptArgument> = emptyList(),
            inputSchema: JsonSchema? = null,
            icons: List<Icon> = emptyList(),
            meta: Map<String, Any>? = null,
            block: suspend PromptScope.() -> List<PromptMessage>,
        ): TachyonServerBuilder =
            prompt(
                descriptor =
                    PromptDescriptor
                        .builder()
                        .name(name)
                        .description(description)
                        .title(title)
                        .arguments(arguments)
                        .inputSchema(inputSchema)
                        .icons(icons)
                        .meta(meta)
                        .build(),
                block = block,
            )

        /**
         * Registers a prebuilt prompt descriptor with a suspending handler block.
         *
         * @param descriptor prompt descriptor
         * @param block handler invoked for prompt requests
         * @return this builder
         */
        @JvmSynthetic
        public fun prompt(
            descriptor: PromptDescriptor,
            block: suspend PromptScope.() -> List<PromptMessage>,
        ): TachyonServerBuilder = this.also { featureRegistrar.prompt(descriptor, block) }

        /**
         * Registers a resource template with the server.
         *
         * @param name The template name.
         * @param uriTemplate The URI template used to identify resources.
         * @param description The optional template description.
         * @param mimeType The optional MIME type of the resources.
         * @param title The optional template title.
         * @param annotations The optional template annotations.
         * @param icons The template icons, or an empty list.
         * @param meta The optional protocol extension metadata.
         * @param block Handles requests for resources matching the template.
         * @return This builder.
         */
        @JvmSynthetic
        @Suppress("LongParameterList")
        public fun resourceTemplate(
            name: String,
            uriTemplate: String,
            description: String? = null,
            mimeType: String? = null,
            title: String? = null,
            annotations: Annotations? = null,
            icons: List<Icon> = emptyList(),
            meta: Map<String, Any>? = null,
            block: suspend TemplateScope.() -> ResourceContents,
        ): TachyonServerBuilder =
            resourceTemplate(
                descriptor =
                    ResourceTemplateDescriptor
                        .builder()
                        .name(name)
                        .uriTemplate(uriTemplate)
                        .description(description)
                        .mimeType(mimeType)
                        .title(title)
                        .annotations(annotations)
                        .icons(icons)
                        .meta(meta)
                        .build(),
                block = block,
            )

        /**
         * Registers a prebuilt resource-template descriptor.
         *
         * @param descriptor resource-template descriptor
         * @param block handler invoked for matching resource requests
         * @return This builder.
         */
        @JvmSynthetic
        public fun resourceTemplate(
            descriptor: ResourceTemplateDescriptor,
            block: suspend TemplateScope.() -> ResourceContents,
        ): TachyonServerBuilder = this.also { featureRegistrar.resourceTemplate(descriptor, block) }

        /**
         * Registers a completion handler for a prompt's arguments.
         *
         * @param promptName the prompt name
         * @param block the suspend function that returns completion candidates
         * @return this builder
         */
        @JvmSynthetic
        public fun promptCompletion(
            promptName: String,
            block: suspend CompletionScope.() -> CompletionResult,
        ): TachyonServerBuilder =
            this.also {
                featureRegistrar.promptCompletion(promptName, block)
            }

        /**
         * Registers a completion handler for a resource template's variables.
         *
         * @param uriOrTemplate the resource URI or template
         * @param block the suspend function that returns completion candidates
         * @return this builder
         */
        @JvmSynthetic
        public fun resourceCompletion(
            uriOrTemplate: String,
            block: suspend CompletionScope.() -> CompletionResult,
        ): TachyonServerBuilder =
            this.also {
                featureRegistrar.resourceCompletion(uriOrTemplate, block)
            }

        /**
         * Registers a tool using a [JsonObject] input schema.
         */
        @JvmSynthetic
        @Suppress("LongParameterList")
        public fun tool(
            name: String,
            description: String? = null,
            title: String? = null,
            inputSchema: JsonObject,
            outputSchema: JsonObject? = null,
            taskSupport: TaskSupport? = null,
            annotations: ToolAnnotations? = null,
            icons: List<Icon>? = null,
            meta: Map<String, Any>? = null,
            block: suspend ToolScope.() -> ToolResult,
        ): TachyonServerBuilder =
            this.tool(
                name = name,
                description = description,
                title = title,
                inputSchema = inputSchema.toJsonSchema(),
                outputSchema = outputSchema.toJsonSchemaOrNull(),
                taskSupport = taskSupport,
                annotations = annotations,
                icons = icons,
                meta = meta,
                block = block,
            )

        public fun name(name: String): TachyonServerBuilder = this.also { delegate.name(name) }

        /**
         * Declares the server stateless: no session is created and no session state is retained.
         * This is the default; call it to write the choice down. Configuring any option inside
         * [session] enables sessions on its own.
         */
        public fun stateless(): TachyonServerBuilder = this.also { delegate.stateless() }

        /** Registers one or more [ServerExtension]s, e.g. from `tachyon-extensions`. */
        public fun extensions(vararg extensions: ServerExtension): TachyonServerBuilder =
            this.also { delegate.withExtensions(*extensions) }

        /** Configures the JSON payload boundary: serde, schema factory, and validators. */
        @OptIn(ExperimentalContracts::class)
        public inline fun json(
            crossinline configure: (@TachyonDsl JsonScope).() -> Unit,
        ): TachyonServerBuilder {
            contract { callsInPlace(configure, InvocationKind.EXACTLY_ONCE) }
            JsonScope().apply(configure).applyTo(delegate)
            return this
        }

        @ExperimentalApi
        public fun pipelineCustomizer(
            customizer: (@TachyonDsl ChannelPipeline).() -> Unit,
        ): TachyonServerBuilder = this.also { delegate.pipelineCustomizer { it.customizer() } }

        @PublishedApi
        internal fun applyPort(port: Int?): TachyonServerBuilder =
            this.also {
                if (port != null) {
                    delegate.port(port)
                } else if (!networkPortExplicitlySet) {
                    delegate.port(NetworkConfig.UNSET_PORT)
                }
            }

        @PublishedApi
        internal fun start(): TachyonServer = build().also { it.start() }

        @PublishedApi
        internal fun build(): TachyonServer =
            DefaultKotlinTachyonServer(delegate.build(), coroutineRuntime)
    }
