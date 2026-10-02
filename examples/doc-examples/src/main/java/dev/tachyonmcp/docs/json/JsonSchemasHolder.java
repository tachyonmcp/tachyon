package dev.tachyonmcp.docs.json;

public final class JsonSchemasHolder {

    public static final String WEATHER_INPUT =
            "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}}}";
    public static final String WEATHER_OUTPUT =
            "{\"type\":\"object\",\"properties\":{\"temp\":{\"type\":\"number\"}},\"required\":[\"temp\"]}";

    private JsonSchemasHolder() {}
}
