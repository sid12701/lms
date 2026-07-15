package com.bhawana.lms.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.IntegrationTestDatabaseTargetGuard;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Opt-in regression against a developer-configured external database ({@code local} profile +
 * repo-root {@code .env}). Excluded from the default {@code mvn test} run; activate with
 * {@code -Pexternal-it} and {@code LMS_IT_EXTERNAL_DB=true}.
 */
@SpringBootTest(properties = "app.rate-limit.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Tag("external-db")
@EnabledIfEnvironmentVariable(named = IntegrationTestDatabaseTargetGuard.EXTERNAL_DB_PROPERTY, matches = "true")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class DocumentUploadExternalDbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void lspMultipartDocumentUploadDoesNotReturn500AgainstConfiguredDatabase() throws Exception {
        DocumentUploadTestSupport.Seed seed = DocumentUploadTestSupport.seedLspApplication(mockMvc, objectMapper);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pan.pdf",
                "application/pdf",
                Files.readAllBytes(Path.of("../postman/assets/sample-pan.pdf"))
        );

        assertThatCode(() -> mockMvc.perform(multipart(
                        "/api/v1/lsp/loan-applications/{applicationId}/documents", seed.applicationId())
                        .file(file)
                        .param("documentType", LoanApplicationDocumentType.PAN_CARD.name())
                        .with(DocumentUploadTestSupport.lspApiClient(seed.clientId(), seed.lspId()))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andDo(print())
                .andExpect(status().is2xxSuccessful()))
                .doesNotThrowAnyException();
    }
}
