/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.List;
import java.util.Objects;

/**
 * Value recovered from an RFC 6570 URI template.
 *
 * <p>Associative values are intentionally unsupported until MCP defines variable schemas that can
 * distinguish them from sequences.
 */
public sealed interface UriTemplateValue permits UriTemplateValue.Scalar, UriTemplateValue.Sequence {

    /**
     * Gets the scalar text represented by this value.
     *
     * @return the scalar value or the sole element of a sequence
     * @throws IllegalStateException if the sequence contains more than one element
     * @throws UnsupportedOperationException if the value type is unsupported
     */
    default String scalarValue() {
        if (this instanceof Scalar s) {
            return s.value;
        } else if (this instanceof Sequence seq) {
            if (seq.values.size() > 1) {
                throw new IllegalStateException("Sequence has more than one element");
            } else {
                return seq.values.getFirst();
            }
        } else {
            throw new UnsupportedOperationException("unsupported value");
        }
    }

    /**
     * Converts this value to a scalar representation.
     *
     * @return this value when it is scalar; otherwise, a scalar containing its value
     */
    default Scalar asScalar() {
        if (this instanceof Scalar s) {
            return s;
        } else {
            return new Scalar(scalarValue());
        }
    }

    /**
     * Provides the value as a sequence.
     *
     * @return this value if it is already a sequence; otherwise, a single-element sequence containing the scalar value
     */
    default Sequence asSequence() {
        if (this instanceof Sequence s) {
            return s;
        } else {
            return new Sequence(List.of(((Scalar) this).value));
        }
    }

    /**
     * A scalar template variable.
     *
     * @param value the scalar value
     */
    record Scalar(String value) implements UriTemplateValue {
        /**
         * Creates a scalar URI template value.
         *
         * @param value the value
         */
        public Scalar {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * An exploded list template variable.
     *
     * @param values the sequence values
     */
    record Sequence(List<String> values) implements UriTemplateValue {
        /**
         * Creates a sequence of URI template values.
         *
         * @param values the values
         */
        public Sequence {
            Objects.requireNonNull(values, "values");
            values = List.copyOf(values);
        }
    }
}
