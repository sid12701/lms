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

    /**
     * F-11: username and email are canonicalised to lowercase at write time
     * (AdminDirectoryService#createUser, LocalBootstrapAdminSyncService,
     * V67 migration). Using LOWER(:param) lets Postgres treat the predicate
     * as `WHERE col = <constant>` so the unique B-tree index can satisfy
     * the lookup. The previous IgnoreCase variants applied UPPER(...) to the
     * column itself and forced a sequential scan.
     */
    @Query("select (count(u) > 0) from AppUser u where u.username = lower(:username)")
    boolean existsByUsername(@Param("username") String username);

    @Query("select (count(u) > 0) from AppUser u where u.email = lower(:email)")
    boolean existsByEmail(@Param("email") String email);

    @Query("select (count(u) > 0) from AppUser u where u.email = lower(:email) and u.id <> :id")
    boolean existsByEmailAndIdNot(@Param("email") String email, @Param("id") UUID id);

    @EntityGraph(attributePaths = {"lsp", "roles"})
    Optional<AppUser> findDetailedById(UUID id);

    @Query("select u from AppUser u where u.username = lower(:username)")
    Optional<AppUser> findByUsername(@Param("username") String username);

    // Login authenticates operators by email (resolved to the internal username
    // before authentication). Mirrors findByUsername: LOWER(:email) keeps the
    // predicate a constant so the unique email index satisfies the lookup.
    @Query("select u from AppUser u where u.email = lower(:email)")
    Optional<AppUser> findByEmail(@Param("email") String email);

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
