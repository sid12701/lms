package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.repo.LspRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class BorrowerAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LspAuditEventRepository lspAuditEventRepository;

    @BeforeEach
    void setUp() {
        borrowerRepository.deleteAllInBatch();
        lspAuditEventRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void opsUserCanListAllBorrowers() throws Exception {
        seedBorrower("Anika Sharma", "ABCDE1234F", "9999999991", "anika@example.com",
                "Bengaluru", "Karnataka");
        seedBorrower("Rahul Shah", "ZXCVB1234N", "9876543210", "rahul@example.com",
                "Delhi", "Delhi");

        mockMvc.perform(get("/api/v1/internal/admin/borrowers").with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.pan == 'ABCDE1234F')].fullName").value("Anika Sharma"))
                .andExpect(jsonPath("$[?(@.pan == 'ZXCVB1234N')].fullName").value("Rahul Shah"))
                .andExpect(jsonPath("$[?(@.pan == 'ABCDE1234F')].mobile").value("9999999991"))
                .andExpect(jsonPath("$[?(@.pan == 'ABCDE1234F')].city").value("Bengaluru"))
                .andExpect(jsonPath("$[?(@.pan == 'ABCDE1234F')].state").value("Karnataka"));
    }

    @Test
    void listBorrowersSupportsCaseInsensitiveSearchAcrossNamePanMobileEmail() throws Exception {
        seedBorrower("Anika Sharma", "ABCDE1234F", "9999999991", "anika@example.com",
                "Bengaluru", "Karnataka");
        seedBorrower("Rahul Shah", "ZXCVB1234N", "9876543210", "rahul@example.com",
                "Delhi", "Delhi");
        seedBorrower("Priya Patel", "LMNOP1234Q", "8888888880", "priya@example.com",
                "Mumbai", "Maharashtra");

        // search by name fragment (case-insensitive)
        mockMvc.perform(get("/api/v1/internal/admin/borrowers")
                        .with(opsUser())
                        .queryParam("q", "rahul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pan").value("ZXCVB1234N"));

        // search by PAN fragment
        mockMvc.perform(get("/api/v1/internal/admin/borrowers")
                        .with(opsUser())
                        .queryParam("q", "abcde"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Anika Sharma"));

        // search by mobile fragment
        mockMvc.perform(get("/api/v1/internal/admin/borrowers")
                        .with(opsUser())
                        .queryParam("q", "888888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pan").value("LMNOP1234Q"));

        // search by email fragment
        mockMvc.perform(get("/api/v1/internal/admin/borrowers")
                        .with(opsUser())
                        .queryParam("q", "priya@"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].pan").value("LMNOP1234Q"));
    }

    @Test
    void listBorrowersPaginatesAndExposesPaginationHeaders() throws Exception {
        seedBorrower("Anika Sharma", "AAAAA1234F", "9999999991", "anika@example.com",
                "Bengaluru", "Karnataka");
        seedBorrower("Rahul Shah", "BBBBB1234N", "9876543210", "rahul@example.com",
                "Delhi", "Delhi");
        seedBorrower("Priya Patel", "CCCCC1234Q", "8888888880", "priya@example.com",
                "Mumbai", "Maharashtra");

        mockMvc.perform(get("/api/v1/internal/admin/borrowers")
                        .with(opsUser())
                        .queryParam("offset", "1")
                        .queryParam("limit", "1")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("X-Limit", "1"))
                .andExpect(header().string("X-Offset", "1"));
    }

    @Test
    void listBorrowersMasksAadharNumberOnTheWire() throws Exception {
        Borrower borrower = new Borrower(
                "Anika Sharma",
                "ABCDE1234F",
                "9999999991",
                "anika@example.com",
                LocalDate.of(1994, 2, 14),
                "F",
                "SINGLE",
                "Suresh Sharma",
                "1234 5678 9012",
                "Bengaluru",
                "Karnataka",
                null, null, null, null,
                "SALARIED",
                null, null, null, null, null,
                new BigDecimal("85000.00"),
                null, null, null, null, null, null, null
        );
        borrowerRepository.save(borrower);

        mockMvc.perform(get("/api/v1/internal/admin/borrowers").with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].aadharNumberMasked").value("XXXXXXXX9012"));
    }

    @Test
    void listBorrowersRejectsUnauthorizedSessions() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/borrowers").with(lspUiRead()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBorrowersExposesVisibleLspIdsPerBorrower() throws Exception {
        Lsp apex = lspRepository.save(new Lsp("APEX-VIS", "Apex Visibility", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("NORTH-VIS", "Northbridge Visibility", LspStatus.ACTIVE));

        Borrower anika = seedBorrower(
                "Anika Sharma", "ABCDE1234F", "9999999991", "anika@example.com",
                "Bengaluru", "Karnataka");
        anika.grantVisibilityTo(apex);
        anika.grantVisibilityTo(north);
        borrowerRepository.save(anika);

        Borrower rahul = seedBorrower(
                "Rahul Shah", "ZXCVB1234N", "9876543210", "rahul@example.com",
                "Delhi", "Delhi");
        rahul.grantVisibilityTo(apex);
        borrowerRepository.save(rahul);

        mockMvc.perform(get("/api/v1/internal/admin/borrowers").with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.pan == 'ABCDE1234F')].visibleLspIds.length()").value(2))
                .andExpect(jsonPath(
                        "$[?(@.pan == 'ABCDE1234F')].visibleLspIds[?(@ == '" + apex.getId() + "')]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                        "$[?(@.pan == 'ABCDE1234F')].visibleLspIds[?(@ == '" + north.getId() + "')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$[?(@.pan == 'ZXCVB1234N')].visibleLspIds.length()").value(1))
                .andExpect(jsonPath(
                        "$[?(@.pan == 'ZXCVB1234N')].visibleLspIds[?(@ == '" + apex.getId() + "')]")
                        .isNotEmpty());
    }

    @Test
    void getBorrowerDetailExposesVisibleLspIds() throws Exception {
        Lsp apex = lspRepository.save(new Lsp("APEX-VIS-D", "Apex Visibility Detail", LspStatus.ACTIVE));
        Lsp north = lspRepository.save(new Lsp("NORTH-VIS-D", "Northbridge Visibility Detail", LspStatus.ACTIVE));

        Borrower borrower = seedBorrower(
                "Anika Sharma", "ABCDE1234F", "9999999991", "anika@example.com",
                "Bengaluru", "Karnataka");
        borrower.grantVisibilityTo(apex);
        borrower.grantVisibilityTo(north);
        borrower = borrowerRepository.save(borrower);

        mockMvc.perform(get("/api/v1/internal/admin/borrowers/" + borrower.getId()).with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").value("ABCDE1234F"))
                .andExpect(jsonPath("$.visibleLspIds.length()").value(2))
                .andExpect(jsonPath("$.visibleLspIds[?(@ == '" + apex.getId() + "')]").isNotEmpty())
                .andExpect(jsonPath("$.visibleLspIds[?(@ == '" + north.getId() + "')]").isNotEmpty());
    }

    private Borrower seedBorrower(
            String fullName,
            String pan,
            String mobile,
            String email,
            String city,
            String state
    ) {
        Borrower borrower = new Borrower(
                fullName,
                pan,
                mobile,
                email,
                null,
                null, null, null, null,
                city,
                state,
                null, null, null, null,
                null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );
        return borrowerRepository.save(borrower);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiRead() {
        return jwt().jwt(jwt -> jwt.subject("lsp.user").claim("roles", List.of("LSP_UI_READ")))
                .authorities(() -> "ROLE_LSP_UI_READ");
    }
}
