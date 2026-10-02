package dev.tachyonmcp.docs.json;

import dev.tachyonmcp.api.json.PayloadSerde;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import java.lang.reflect.Type;
import java.util.Map;

public final class MarkingSerde implements PayloadSerde {

    private final JacksonPayloadSerde delegate = new JacksonPayloadSerde();

    @Override
    public <T> String serialize(T value) {
        return delegate.serialize(Map.of("serializedBy", "custom", "value", value));
    }

    @Override
    public <T> T deserialize(String json, Type targetType) {
        return delegate.deserialize(json, targetType);
    }
}
