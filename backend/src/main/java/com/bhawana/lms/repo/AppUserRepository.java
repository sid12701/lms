package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);

    @EntityGraph(attributePaths = {"lsp", "roles"})
    Optional<AppUser> findDetailedById(UUID id);

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    @Query("""
            select count(distinct u)
            from AppUser u
            join u.roles r
            where r.code = :role
              and u.status = com.bhawana.lms.domain.UserStatus.ACTIVE
              and u.id <> :excludeUserId
            """)
    long countActiveUsersWithRoleExcluding(
            @Param("role") RoleCode role,
            @Param("excludeUserId") UUID excludeUserId
    );

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
