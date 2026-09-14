# Package `dev.tachyonmcp.kotlin.server.json.ktschema`

Experimental kt-schema integration for Tachyon MCP Kotlin.

Provides a runtime [`JsonSchemaFactory`](https://github.com/tachyonmcp/tachyon/blob/main/docs)
that generates a JSON schema by reflecting on the class, backed by kt-schema's
`ReflectionClassJsonSchemaGenerator`.

Runs after the build-time resource factory (`KtSchemaResourceFactory`): generates a schema
whenever no codegen resource exists for the type.

Ships in the dedicated `tachyon-kotlin-kt-schema` integration artifact, which declares
`kt-schema-generator-json-jvm` as a regular (non-optional) dependency. The provider therefore
always loads once that artifact is on the classpath — add it explicitly to use `typedTool`.
