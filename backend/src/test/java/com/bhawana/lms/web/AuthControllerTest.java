package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void tokenEndpointReturnsJwtForBootstrapUser() throws Exception {
        AuthController.LoginRequest loginRequest = new AuthController.LoginRequest("test.admin", "TestPassword123!");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/internal/system/context")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test.admin"))
                .andExpect(jsonPath("$.roles[0]").value("SYSTEM_ADMIN"));
    }

    @Test
    void protectedEndpointRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/internal/system/context"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsJwtAuthorities() throws Exception {
        mockMvc.perform(get("/api/v1/internal/system/context")
                        .with(jwt().jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ops.user"))
                .andExpect(jsonPath("$.roles[0]").value("OPS_USER"));
    }
}
