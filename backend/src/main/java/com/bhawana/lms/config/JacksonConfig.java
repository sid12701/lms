package com.bhawana.lms.config;

import com.bhawana.lms.common.api.StrictJsonUnknownPropertyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper objectMapper = builder
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        // Lenient by default; @StrictJson request types (the LSP write surface)
        // reject unknown fields via this handler.
        objectMapper.addHandler(new StrictJsonUnknownPropertyHandler());
        return objectMapper;
    }
}
