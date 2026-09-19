/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverse parser for RFC 6570 URI templates with scalar and unambiguous exploded list values.
 */
public final class UriTemplate {
    private static final int MAX_TEMPLATE_LENGTH = 1_024;
    private static final int MAX_RAW_URI_LENGTH = 8_192;
    private static final String UNRESERVED_VALUE = "(?:%[0-9A-Fa-f]{2}|[A-Za-z0-9._~-])*";
    private static final String RESERVED_VALUE = "(?:%[0-9A-Fa-f]{2}|[A-Za-z0-9._~:/?#@!$&'()*+,;=\\[\\]-])*";

    private final Pattern pattern;
    private final List<Capture> captures;

    private UriTemplate(Pattern pattern, List<Capture> captures) {
        this.pattern = pattern;
        this.captures = List.copyOf(captures);
    }

    /**
     * Creates a reverse parser for an RFC 6570 URI template.
     *
     * @param uriTemplate the URI template to compile
     * @return a parser that extracts decoded values from matching URIs
     * @throws NullPointerException if {@code uriTemplate} is {@code null}
     * @throws IllegalArgumentException if the template is too long or malformed
     */
    public static UriTemplate create(String uriTemplate) {
        Objects.requireNonNull(uriTemplate, "uriTemplate");
        if (uriTemplate.length() > MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException("URI template is too long");
        }

        var regex = new StringBuilder("^");
        var captures = new ArrayList<Capture>();
        int cursor = 0;
        int group = 1;

        while (cursor < uriTemplate.length()) {
            int expressionStart = uriTemplate.indexOf('{', cursor);
            if (expressionStart < 0) {
                appendLiteral(regex, uriTemplate.substring(cursor));
                cursor = uriTemplate.length();
                continue;
            }

            appendLiteral(regex, uriTemplate.substring(cursor, expressionStart));
            int expressionEnd = uriTemplate.indexOf('}', expressionStart + 1);
            int nestedExpression = uriTemplate.indexOf('{', expressionStart + 1);
            if (expressionEnd < 0 || (nestedExpression >= 0 && nestedExpression < expressionEnd)) {
                throw malformed(uriTemplate);
            }

            group = appendExpression(regex, captures, group, uriTemplate.substring(expressionStart + 1, expressionEnd));
            cursor = expressionEnd + 1;
        }

        regex.append('$');
        return new UriTemplate(Pattern.compile(regex.toString()), captures);
    }

    /**
     * Parses a raw URI according to this template and extracts decoded variable values.
     *
     * @param rawUri the URI to parse
     * @return an unmodifiable map of variable names to decoded scalar or sequence values
     * @throws IllegalArgumentException if the URI is invalid, exceeds the supported length, does not match the template, or contains undecodable values
     */
    public Map<String, UriTemplateValue> parse(String rawUri) {
        Objects.requireNonNull(rawUri, "rawUri");
        if (rawUri.length() > MAX_RAW_URI_LENGTH) {
            throw noMatch(rawUri);
        }

        try {
            new URI(rawUri);
        } catch (URISyntaxException exception) {
            throw noMatch(rawUri);
        }

        Matcher matcher = pattern.matcher(rawUri);
        if (!matcher.matches()) {
            throw noMatch(rawUri);
        }

        var values = new LinkedHashMap<String, ParsedValue>();
        for (Capture capture : captures) {
            String rawValue = matcher.group(capture.group());
            if (rawValue == null) {
                rawValue = "";
            }

            UriTemplateValue value;
            if (capture.variable().explode()) {
                value = decodeSequence(rawValue, capture, rawUri);
            } else {
                String decoded = decode(rawValue).orElseThrow(() -> noMatch(rawUri));
                if (capture.variable().prefixLength() > 0
                        && decoded.codePointCount(0, decoded.length())
                                > capture.variable().prefixLength()) {
                    throw noMatch(rawUri);
                }
                value = new UriTemplateValue.Scalar(decoded);
            }

            merge(values, capture, value, rawUri);
        }

        var result = new LinkedHashMap<String, UriTemplateValue>();
        values.forEach((name, value) -> result.put(name, value.value()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Appends the regular-expression representation of a URI template expression and records its captures.
     *
     * @param  expression the URI template expression to append
     * @return the next available capture-group index
     * @throws IllegalArgumentException if the expression is invalid or cannot be reversed unambiguously
     */
    private static int appendExpression(
            StringBuilder regex, List<Capture> captures, int firstGroup, String expression) {
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("Empty URI template expression");
        }

        Operator operator = Operator.from(expression.charAt(0));
        String variableList = operator.explicit() ? expression.substring(1) : expression;
        if (variableList.isEmpty()) {
            throw new IllegalArgumentException("URI template expression has no variables");
        }

        String[] rawVariables = variableList.split(",", -1);
        var variables = new ArrayList<Variable>(rawVariables.length);
        for (String rawVariable : rawVariables) {
            variables.add(parseVariable(rawVariable));
        }

        if (operator.allowReserved() && variables.size() > 1) {
            throw new IllegalArgumentException(
                    "Reserved expansion with multiple variables cannot be reversed unambiguously");
        }
        if (variables.stream().anyMatch(Variable::explode)) {
            if (variables.size() > 1) {
                throw new IllegalArgumentException("An exploded URI template variable must be the sole variable");
            }
            Variable variable = variables.getFirst();
            if (!operator.supportsExplodedSequence()) {
                throw new IllegalArgumentException(
                        "Exploded URI template variable cannot be reversed without a variable schema");
            }
            appendExplodedExpression(regex, operator, variable);
            captures.add(new Capture(firstGroup, variable, operator));
            return firstGroup + 1;
        }

        regex.append(Pattern.quote(operator.prefix()));
        int group = firstGroup;
        for (int index = 0; index < variables.size(); index++) {
            if (index > 0) {
                regex.append(Pattern.quote(operator.separator()));
            }

            Variable variable = variables.get(index);
            if (operator.named()) {
                regex.append(Pattern.quote(variable.name()));
                if (operator == Operator.MATRIX) {
                    regex.append("(?:=(").append(valuePattern(operator)).append("))?");
                } else {
                    regex.append("=(").append(valuePattern(operator)).append(')');
                }
            } else {
                regex.append('(').append(valuePattern(operator)).append(')');
            }

            captures.add(new Capture(group++, variable, operator));
        }
        return group;
    }

    /**
     * Appends the regular expression for an exploded variable sequence.
     *
     * @param regex the regular expression being constructed
     * @param operator the URI template operator defining the sequence format
     * @param variable the variable whose name is used for named sequence elements
     */
    private static void appendExplodedExpression(StringBuilder regex, Operator operator, Variable variable) {
        String value = valuePattern(operator);
        switch (operator) {
            case LABEL, PATH ->
                regex.append("((?:")
                        .append(Pattern.quote(operator.prefix()))
                        .append(value)
                        .append(")*)");
            case MATRIX ->
                regex.append("((?:")
                        .append(Pattern.quote(operator.prefix()))
                        .append(Pattern.quote(variable.name()))
                        .append("(?:=")
                        .append(value)
                        .append(")?)*)");
            case QUERY, CONTINUATION ->
                regex.append("((?:")
                        .append(Pattern.quote(operator.prefix()))
                        .append(Pattern.quote(variable.name()))
                        .append('=')
                        .append(value)
                        .append("(?:")
                        .append(Pattern.quote(operator.separator()))
                        .append(Pattern.quote(variable.name()))
                        .append('=')
                        .append(value)
                        .append(")*)?)");
            default -> throw new IllegalStateException("Unsupported exploded sequence operator: " + operator);
        }
    }

    /**
     * Parses a URI template variable, including optional prefix-length and explode modifiers.
     *
     * @param rawVariable the variable expression to parse
     * @return the parsed variable metadata
     * @throws IllegalArgumentException if the expression is empty or has an invalid name or modifier
     */
    private static Variable parseVariable(String rawVariable) {
        if (rawVariable.isEmpty()) {
            throw new IllegalArgumentException("Empty URI template variable");
        }
        boolean explode = rawVariable.endsWith("*");
        String variable = explode ? rawVariable.substring(0, rawVariable.length() - 1) : rawVariable;

        int colon = variable.indexOf(':');
        if (colon < 0) {
            validateVariableName(variable);
            return new Variable(variable, 0, explode);
        }
        if (explode) {
            throw new IllegalArgumentException("URI template variable cannot combine prefix and explode modifiers");
        }
        if (colon == 0 || colon != variable.lastIndexOf(':')) {
            throw new IllegalArgumentException("Invalid URI template prefix modifier");
        }

        String name = variable.substring(0, colon);
        String rawPrefixLength = variable.substring(colon + 1);
        validateVariableName(name);
        if (!rawPrefixLength.matches("[1-9][0-9]{0,3}")) {
            throw new IllegalArgumentException("Invalid URI template prefix length");
        }
        return new Variable(name, Integer.parseInt(rawPrefixLength), false);
    }

    /**
     * Validates the syntax of a URI template variable name.
     *
     * @param name the variable name to validate
     * @throws IllegalArgumentException if the name contains invalid characters or separators
     */
    private static void validateVariableName(String name) {
        boolean expectingCharacter = true;
        for (int index = 0; index < name.length(); ) {
            char current = name.charAt(index);
            if (current == '.') {
                if (expectingCharacter) {
                    throw new IllegalArgumentException("Invalid URI template variable name: " + name);
                }
                expectingCharacter = true;
                index++;
                continue;
            }
            if (isAsciiLetter(current) || isAsciiDigit(current) || current == '_') {
                expectingCharacter = false;
                index++;
                continue;
            }
            if (current == '%'
                    && index + 2 < name.length()
                    && hexValue(name.charAt(index + 1)) >= 0
                    && hexValue(name.charAt(index + 2)) >= 0) {
                expectingCharacter = false;
                index += 3;
                continue;
            }
            throw new IllegalArgumentException("Invalid URI template variable name: " + name);
        }
        if (expectingCharacter) {
            throw new IllegalArgumentException("Invalid URI template variable name: " + name);
        }
    }

    /**
     * Appends a validated URI template literal to the regular expression.
     *
     * @param regex   the regular expression being constructed
     * @param literal the literal template segment to validate and append
     * @throws IllegalArgumentException if the literal contains invalid characters or percent encoding
     */
    private static void appendLiteral(StringBuilder regex, String literal) {
        var encoded = new StringBuilder(literal.length());
        for (int index = 0; index < literal.length(); ) {
            int codePoint = literal.codePointAt(index);
            if (codePoint == '%') {
                if (index + 2 >= literal.length()
                        || hexValue(literal.charAt(index + 1)) < 0
                        || hexValue(literal.charAt(index + 2)) < 0) {
                    throw new IllegalArgumentException("Invalid percent-encoded URI template literal");
                }
                encoded.append(literal, index, index + 3);
                index += 3;
                continue;
            }
            if (!isValidLiteral(codePoint)) {
                throw new IllegalArgumentException("Invalid URI template literal");
            }
            if (codePoint <= 0x7F) {
                encoded.append((char) codePoint);
            } else {
                appendPercentEncoded(encoded, codePoint);
            }
            index += Character.charCount(codePoint);
        }
        regex.append(Pattern.quote(encoded.toString()));
    }

    /**
     * Validates whether a code point can appear in URI template literals.
     *
     * @param  codePoint the Unicode code point to validate
     * @return           {@code true} if the code point is allowed in URI template literals,
     *                   {@code false} otherwise
     */
    private static boolean isValidLiteral(int codePoint) {
        if (codePoint > 0x7F) {
            return !Character.isISOControl(codePoint)
                    && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE);
        }
        return codePoint > 0x20
                && codePoint != 0x7F
                && codePoint != '"'
                && codePoint != '<'
                && codePoint != '>'
                && codePoint != '\\'
                && codePoint != '^'
                && codePoint != '`'
                && codePoint != '{'
                && codePoint != '|'
                && codePoint != '}';
    }

    /**
     * Appends the UTF-8 percent-encoded representation of a Unicode code point.
     *
     * @param target the builder to receive the encoded bytes
     * @param codePoint the Unicode code point to encode
     */
    private static void appendPercentEncoded(StringBuilder target, int codePoint) {
        byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            target.append('%')
                    .append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(unsigned & 0x0F, 16)));
        }
    }

    private static String valuePattern(Operator operator) {
        return operator.allowReserved() ? RESERVED_VALUE : UNRESERVED_VALUE;
    }

    /**
     * Decodes a raw value containing percent-encoded UTF-8 bytes.
     *
     * @param raw the raw encoded value
     * @return the decoded string, or an empty optional if decoding fails
     */
    private static Optional<String> decode(String raw) {
        var bytes = new ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current > 0x7F) {
                return Optional.empty();
            }
            if (current != '%') {
                bytes.write((byte) current);
                continue;
            }

            int high = hexValue(raw.charAt(index + 1));
            int low = hexValue(raw.charAt(index + 2));
            bytes.write((high << 4) | low);
            index += 2;
        }

        try {
            return Optional.of(StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString());
        } catch (CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    /**
     * Decodes the items in an exploded URI template variable sequence.
     *
     * @param raw     the matched raw sequence
     * @param capture the variable and operator metadata for the sequence
     * @param rawUri  the URI used when reporting a non-matching sequence
     * @return the decoded sequence values
     */
    private static UriTemplateValue.Sequence decodeSequence(String raw, Capture capture, String rawUri) {
        if (raw.isEmpty()) {
            return new UriTemplateValue.Sequence(List.of());
        }

        String[] rawItems = raw.substring(capture.operator().prefix().length())
                .split(Pattern.quote(capture.operator().separator()), -1);

        var values = new ArrayList<String>(rawItems.length);
        for (String rawItem : rawItems) {
            var encodedValue = getEncodedValue(capture, rawUri, rawItem);
            values.add(decode(encodedValue).orElseThrow(() -> noMatch(rawUri)));
        }
        return new UriTemplateValue.Sequence(values);
    }

    private static String getEncodedValue(Capture capture, String rawUri, String rawItem) {
        String encodedValue = rawItem;
        if (capture.operator().named()) {
            String name = capture.variable().name();
            if (rawItem.equals(name) && capture.operator() == Operator.MATRIX) {
                encodedValue = "";
            } else {
                String prefix = name + "=";
                if (!rawItem.startsWith(prefix)) {
                    throw noMatch(rawUri);
                }
                encodedValue = rawItem.substring(prefix.length());
            }
        }
        return encodedValue;
    }

    /**
     * Merges a captured variable value into the parsed results, resolving compatible
     * prefix captures and rejecting conflicting values.
     *
     * @param values   the accumulated parsed values
     * @param capture  metadata for the captured variable
     * @param value    the captured variable value
     * @param rawUri   the URI being parsed
     * @throws IllegalArgumentException if the value conflicts with an existing capture
     */
    private static void merge(Map<String, ParsedValue> values, Capture capture, UriTemplateValue value, String rawUri) {
        ParsedValue current = values.get(capture.variable().name());
        ParsedValue candidate = new ParsedValue(value, capture.variable().prefixLength() > 0);
        if (current == null) {
            values.put(capture.variable().name(), candidate);
            return;
        }
        if (current.value() instanceof UriTemplateValue.Scalar(String value1)
                && candidate.value() instanceof UriTemplateValue.Scalar(String value2)
                && current.prefix()
                && !candidate.prefix()
                && value2.startsWith(value1)) {
            values.put(capture.variable().name(), candidate);
            return;
        }
        if (current.value() instanceof UriTemplateValue.Scalar(String value1)
                && candidate.value() instanceof UriTemplateValue.Scalar(String value2)
                && !current.prefix()
                && candidate.prefix()
                && value1.startsWith(value2)) {
            return;
        }
        if (!current.value().equals(candidate.value())) {
            throw noMatch(rawUri);
        }
    }

    private static IllegalArgumentException malformed(String uriTemplate) {
        return new IllegalArgumentException("Malformed URI template: " + uriTemplate);
    }

    /**
     * Creates an exception indicating that a URI does not match the template.
     *
     * @param rawUri the URI that failed to match
     * @return the corresponding mismatch exception
     */
    private static IllegalArgumentException noMatch(String rawUri) {
        return new IllegalArgumentException("URI does not match template: " + rawUri);
    }

    /**
     * Converts a hexadecimal character to its numeric value.
     *
     * @param value the hexadecimal character
     * @return the value from 0 to 15, or {@code -1} if the character is not hexadecimal
     */
    private static int hexValue(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        return -1;
    }

    /**
     * Determines whether a character is an ASCII letter.
     *
     * @param value the character to check
     * @return {@code true} if the character is an ASCII letter, {@code false} otherwise
     */
    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    /**
     * Determines whether a character is an ASCII digit.
     *
     * @param value the character to evaluate
     * @return {@code true} if the character is between {@code '0'} and {@code '9'}, {@code false} otherwise
     */
    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private enum Operator {
        SIMPLE(false, "", ",", false, false),
        RESERVED(true, "", ",", false, true),
        FRAGMENT(true, "#", ",", false, true),
        LABEL(true, ".", ".", false, false),
        PATH(true, "/", "/", false, false),
        MATRIX(true, ";", ";", true, false),
        QUERY(true, "?", "&", true, false),
        CONTINUATION(true, "&", "&", true, false);

        private final boolean explicit;
        private final String prefix;
        private final String separator;
        private final boolean named;
        private final boolean allowReserved;

        Operator(boolean explicit, String prefix, String separator, boolean named, boolean allowReserved) {
            this.explicit = explicit;
            this.prefix = prefix;
            this.separator = separator;
            this.named = named;
            this.allowReserved = allowReserved;
        }

        /**
         * Identifies the URI template operator represented by a character.
         *
         * @param candidate the character to classify
         * @return the corresponding operator, or {@code SIMPLE} when no explicit operator is represented
         * @throws IllegalArgumentException if the character is a reserved but unsupported operator
         */
        static Operator from(char candidate) {
            return switch (candidate) {
                case '+' -> RESERVED;
                case '#' -> FRAGMENT;
                case '.' -> LABEL;
                case '/' -> PATH;
                case ';' -> MATRIX;
                case '?' -> QUERY;
                case '&' -> CONTINUATION;
                case '=', ',', '!', '@', '|' ->
                    throw new IllegalArgumentException("Reserved URI template operator: " + candidate);
                default -> SIMPLE;
            };
        }

        boolean explicit() {
            return explicit;
        }

        String prefix() {
            return prefix;
        }

        String separator() {
            return separator;
        }

        /**
         * Indicates whether the operator uses named variables.
         *
         * @return {@code true} if variables include names, {@code false} otherwise
         */
        boolean named() {
            return named;
        }

        /**
         * Indicates whether the operator permits reserved URI characters in variable values.
         *
         * @return {@code true} if reserved characters are permitted, {@code false} otherwise
         */
        boolean allowReserved() {
            return allowReserved;
        }

        /**
         * Determines whether the operator supports exploded sequence variables.
         *
         * @return {@code true} for label, path, matrix, query, and continuation operators;
         *         {@code false} otherwise
         */
        boolean supportsExplodedSequence() {
            return this == LABEL || this == PATH || this == MATRIX || this == QUERY || this == CONTINUATION;
        }
    }

    private record Variable(String name, int prefixLength, boolean explode) {}

    private record Capture(int group, Variable variable, Operator operator) {}

    private record ParsedValue(UriTemplateValue value, boolean prefix) {}
}
