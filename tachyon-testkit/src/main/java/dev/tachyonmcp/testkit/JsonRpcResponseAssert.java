/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.net.http.HttpResponse;
import java.util.function.Consumer;
import org.assertj.core.api.AbstractAssert;
import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fluent, branch-safe assertions over a JSON-RPC response envelope. */
public class JsonRpcResponseAssert extends AbstractAssert<JsonRpcResponseAssert, JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final @Nullable HttpResponse<String> httpResponse;

    protected JsonRpcResponseAssert(@Nullable HttpResponse<String> httpResponse, JsonNode envelope) {
        super(envelope, JsonRpcResponseAssert.class);
        this.httpResponse = httpResponse;
    }

    /** Creates assertions for a parsed JSON-RPC response envelope.
     *
     * @param envelope the parsed JSON-RPC response envelope
     * @return new assertions
     */
    public static JsonRpcResponseAssert assertThat(JsonNode envelope) {
        return new JsonRpcResponseAssert(null, envelope);
    }

    /** Creates assertions for an HTTP response containing a JSON-RPC envelope.
     *
     * @param response the HTTP response
     * @return new assertions
     */
    public static JsonRpcResponseAssert assertThat(HttpResponse<String> response) {
        return new JsonRpcResponseAssert(response, MAPPER.readTree(response.body()));
    }

    /**
     * Creates assertions for one JSON-RPC envelope within an SSE HTTP response.
     *
     * @param response SSE HTTP response
     * @param requestId JSON-RPC request id
     * @return assertions for the matching JSON-RPC response
     */
    public static JsonRpcResponseAssert assertThat(HttpResponse<String> response, String requestId) {
        return assertThatJsonRpcResponse(McpClient.extractJsonRpcResponse(response.body(), requestId));
    }

    /** Creates assertions for a raw JSON-RPC response body.
     *
     * @param json the raw JSON-RPC response body
     * @return new assertions
     */
    public static JsonRpcResponseAssert assertThatJsonRpcResponse(String json) {
        return new JsonRpcResponseAssert(null, MAPPER.readTree(json));
    }

    /** Verifies the success branch and returns success-only assertions.
     *
     * @return success-only assertions
     */
    public JsonRpcSuccessAssert isSuccess() {
        isNotNull();
        if (actual.has("error") || !actual.has("result")) {
            failWithMessage("Expected a successful JSON-RPC response but was: %s", actual);
        }
        return new JsonRpcSuccessAssert(actual);
    }

    /** Verifies the error branch and returns error-only assertions.
     *
     * @return error-only assertions
     */
    public JsonRpcErrorAssert isJsonRpcError() {
        isNotNull();
        if (actual.has("result") || !actual.path("error").isObject()) {
            failWithMessage("Expected a JSON-RPC error response but was: %s", actual);
        }
        return new JsonRpcErrorAssert(httpResponse, actual);
    }

    /** Assertions available only after verifying a successful response. */
    public static final class JsonRpcSuccessAssert extends AbstractAssert<JsonRpcSuccessAssert, JsonNode> {

        private JsonRpcSuccessAssert(JsonNode envelope) {
            super(envelope, JsonRpcSuccessAssert.class);
        }

        /** Verifies the JSON-RPC response id.
         *
         * @param expected the expected response id
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasId(Object expected) {
            var expectedId = MAPPER.valueToTree(expected);
            var id = actual.path("id");
            if (!id.equals(expectedId)) {
                failWithMessage("Expected JSON-RPC id <%s> but was <%s> in: %s", expectedId, id, actual);
            }
            return this;
        }

        public JsonNode result() {
            return actual.path("result");
        }

        /** Verifies a successful tool result reports {@code isError: true}.
         *
         * @return this assertion
         */
        public JsonRpcSuccessAssert isToolError() {
            var isError = result().path("isError");
            if (!isError.isBoolean() || !isError.asBoolean()) {
                failWithMessage("Expected tool result 'isError' to be true in: %s", actual);
            }
            return this;
        }

        /** Verifies result content contains an equal text block.
         *
         * @param expectedText the expected text content
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasTextContent(String expectedText) {
            var content = result().path("content");
            for (var block : content) {
                if ("text".equals(block.path("type").asString())
                        && expectedText.equals(block.path("text").asString())) {
                    return this;
                }
            }
            failWithMessage("Expected result content to contain text <%s> but was: %s", expectedText, content);
            return this;
        }

        /** Verifies result content contains a text block.
         *
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasTextContent() {
            var content = result().path("content");
            for (var block : content) {
                if ("text".equals(block.path("type").asString())
                        && block.path("text").isString()) {
                    return this;
                }
            }
            failWithMessage("Expected result content to contain a text block but was: %s", content);
            return this;
        }

        /**
         * Verifies result content is a non-empty array.
         *
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasContent() {
            var content = result().path("content");
            if (!content.isArray() || content.isEmpty()) {
                failWithMessage("Expected result 'content' to be a non-empty array in: %s", actual);
            }
            return this;
        }

        /**
         * Verifies result content contains exactly the expected blocks, in order.
         *
         * @param expected the expected content blocks
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasContentExactly(JsonNode... expected) {
            var content = result().path("content");
            var expectedContent = MAPPER.valueToTree(expected);
            if (!content.equals(expectedContent)) {
                failWithMessage("Expected result content <%s> but was <%s> in: %s", expectedContent, content, actual);
            }
            return this;
        }

        /**
         * Verifies the complete JSON-RPC result value.
         *
         * @param expected the expected result
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasResult(JsonNode expected) {
            var result = result();
            if (!result.equals(expected)) {
                failWithMessage("Expected result <%s> but was <%s> in: %s", expected, result, actual);
            }
            return this;
        }

        /**
         * Verifies the complete JSON-RPC result value.
         *
         * @param expectedJson the expected result JSON
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasResult(@Language("json") String expectedJson) {
            assertThatJson(result()).isEqualTo(expectedJson);
            return this;
        }

        /**
         * Verifies the MCP result type.
         *
         * @param expected the expected result type
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasResultType(String expected) {
            var resultType = result().path("resultType").asString(null);
            if (!expected.equals(resultType)) {
                failWithMessage("Expected resultType <%s> but was <%s> in: %s", expected, resultType, actual);
            }
            return this;
        }

        /** Verifies {@code structuredContent} equals the expected JSON.
         *
         * @param expected the expected structured content
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasStructuredContent(JsonNode expected) {
            var structuredContent = result().path("structuredContent");
            if (!structuredContent.equals(expected)) {
                failWithMessage(
                        "Expected structuredContent <%s> but was <%s> in: %s", expected, structuredContent, actual);
            }
            return this;
        }

        /**
         * Verifies {@code structuredContent} equals the expected JSON text.
         *
         * @param expectedJson the expected structured content as JSON text
         * @return this assertion
         */
        public JsonRpcSuccessAssert hasStructuredContent(@Language("json") String expectedJson) {
            assertThatJson(result().path("structuredContent")).isEqualTo(expectedJson);
            return this;
        }

        /** Verifies {@code structuredContent} is absent from the result.
         *
         * @return this assertion
         */
        public JsonRpcSuccessAssert doesNotHaveStructuredContent() {
            if (result().has("structuredContent")) {
                failWithMessage("Expected no structuredContent in: %s", actual);
            }
            return this;
        }
    }

    /**
     * Assertions available only after verifying an error response.
     */
    public static final class JsonRpcErrorAssert extends AbstractAssert<JsonRpcErrorAssert, JsonNode> {

        private final @Nullable HttpResponse<String> httpResponse;

        private JsonRpcErrorAssert(@Nullable HttpResponse<String> httpResponse, JsonNode envelope) {
            super(envelope, JsonRpcErrorAssert.class);
            this.httpResponse = httpResponse;
        }

        /** Verifies the JSON-RPC response id.
         *
         * @param expected the expected response id
         * @return this assertion
         */
        public JsonRpcErrorAssert hasId(Object expected) {
            var expectedId = MAPPER.valueToTree(expected);
            var id = actual.path("id");
            if (!id.equals(expectedId)) {
                failWithMessage("Expected JSON-RPC id <%s> but was <%s> in: %s", expectedId, id, actual);
            }
            return this;
        }

        /**
         * Verifies the HTTP status code.
         *
         * @param expectedHttpStatusCode the expected HTTP status code
         * @return this assertion
         */
        public JsonRpcErrorAssert hasHttpStatusCode(int expectedHttpStatusCode) {
            if (httpResponse == null) {
                failWithMessage("HttpResponse must be provided for http status code assertions");
            }
            if (httpResponse.statusCode() != expectedHttpStatusCode) {
                failWithMessage(
                        "Expected HTTP status code <%s> but was <%s> in: %s",
                        expectedHttpStatusCode, httpResponse.statusCode());
            }
            return this;
        }

        /** Verifies the JSON-RPC error code.
         *
         * @param expectedCode the expected error code
         * @return this assertion
         */
        public JsonRpcErrorAssert hasErrorCode(int expectedCode) {
            var code = actual.path("error").path("code");
            if (!code.isInt() || code.asInt() != expectedCode) {
                failWithMessage("Expected JSON-RPC error code <%s> but was <%s> in: %s", expectedCode, code, actual);
            }
            return this;
        }

        /** Verifies the exact JSON-RPC error message.
         *
         * @param expectedMessage the expected error message
         * @return this assertion
         */
        public JsonRpcErrorAssert hasErrorMessage(String expectedMessage) {
            var message = actual.path("error").path("message").asString(null);
            if (!expectedMessage.equals(message)) {
                failWithMessage(
                        "Expected JSON-RPC error message <%s> but was <%s> in: %s", expectedMessage, message, actual);
            }
            return this;
        }

        /** Verifies the JSON-RPC error message contains the expected text.
         *
         * @param expectedText the expected text substring
         * @return this assertion
         */
        public JsonRpcErrorAssert hasErrorMessageContaining(String expectedText) {
            var message = actual.path("error").path("message").asString("");
            if (!message.contains(expectedText)) {
                failWithMessage(
                        "Expected JSON-RPC error message containing <%s> but was <%s> in: %s",
                        expectedText, message, actual);
            }
            return this;
        }

        /** Runs an assertion against {@code error.data}.
         *
         * @param assertion the assertion to run against the error data
         * @return this assertion
         */
        public JsonRpcErrorAssert hasErrorDataSatisfying(Consumer<JsonNode> assertion) {
            assertion.accept(actual.path("error").path("data"));
            return this;
        }

        /**
         * If the server does not implement the requested RPC method,
         * it MUST respond with 404 Not Found and a JSON-RPC error with code -32601 (Method not found).
         * The JSON-RPC error body distinguishes this case from a 404 returned by a legacy HTTP+SSE server that does not host the modern MCP endpoint (see Backward Compatibility).
         *
         * @return this assertion
         */
        public JsonRpcErrorAssert isMethodNotFound() {
            final JsonRpcErrorAssert result;
            if (httpResponse != null) {
                result = hasHttpStatusCode(404);
            } else {
                result = this;
            }
            return result.hasErrorCode(-32601).hasErrorMessage("Method not found");
        }

        /**
         * Verifies the complete JSON-RPC error value.
         *
         * @param expected the expected error object
         * @return this assertion
         */
        public JsonRpcErrorAssert hasError(JsonNode expected) {
            var error = actual.path("error");
            if (!error.equals(expected)) {
                failWithMessage("Expected error <%s> but was <%s> in: %s", expected, error, actual);
            }
            return this;
        }
    }
}
