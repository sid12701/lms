package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    @EntityGraph(attributePaths = {"lsp", "roles"})
    List<AppUser> findAllByOrderByUsernameAsc();

    @EntityGraph(attributePaths = {"lsp", "roles"})
    List<AppUser> findByLsp_IdOrderByUsernameAsc(UUID lspId);

    @Query("""
            select user.lsp.id as lspId,
                   count(user) as userCount
            from AppUser user
            where user.lsp is not null
            group by user.lsp.id
            """)
    List<LspUserCountProjection> countUsersByLsp();

    interface LspUserCountProjection {
        UUID getLspId();

        long getUserCount();
    }
}
