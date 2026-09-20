package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

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

    /**
     * Principal-row lock without a nullable outer-join FOR UPDATE. Locks only the
     * base {@code app_user} row (no EntityGraph here); callers initialize roles/LSP
     * with separate selects under the same transaction after holding this lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);

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

    /**
     * M20: bounded admin-directory search. Filters apply to the full dataset
     * before pagination; {@code role} matches membership in the user's granted
     * role set (not a collapsed primary role). The entity graph keeps the
     * page's lsp/roles fetch to one join instead of N+1 selects.
     */
    @EntityGraph(attributePaths = {"lsp", "roles"})
    @Query("""
            select u from AppUser u
            where (:status is null or u.status = :status)
              and (:lspId is null or u.lsp.id = :lspId)
              and (:role is null or exists (
                  select 1 from AppUser u2 join u2.roles r
                  where u2.id = u.id and r.code = :role))
              and (:queryLike is null
                   or lower(u.username) like :queryLike
                   or lower(u.email) like :queryLike)
            """)
    Page<AppUser> searchUsers(
            @Param("status") UserStatus status,
            @Param("role") RoleCode role,
            @Param("lspId") UUID lspId,
            @Param("queryLike") String queryLike,
            Pageable pageable
    );

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
