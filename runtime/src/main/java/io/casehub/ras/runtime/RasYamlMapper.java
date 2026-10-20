package io.casehub.ras.runtime;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.platform.api.expression.ExpressionEvaluator;

class RasYamlMapper {

    private static final ObjectMapper INSTANCE = build();

    static ObjectMapper instance() {
        return INSTANCE;
    }

    private static ObjectMapper build() {
        var mapper = new ObjectMapper(new YAMLFactory());
        mapper.registerModule(new JavaTimeModule());
        var module = new SimpleModule();
        module.addDeserializer(ExpressionEvaluator.class, new ExpressionEvaluatorDeserializer());
        mapper.registerModule(module);
        mapper.enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        return mapper;
    }
}
