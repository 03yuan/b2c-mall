package com.ysh.b2cmall.user.web.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Objects;

/**
 * 严格文本字段反序列化：只接受 JSON 字符串，数字/布尔等类型直接判为解析失败（400）。
 */
public class StrictTextDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (!Objects.equals(p.currentToken(), JsonToken.VALUE_STRING)) {
            return (String) ctxt.handleUnexpectedToken(String.class, p);
        }
        return p.getText();
    }
}