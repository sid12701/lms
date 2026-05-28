-- F-11: canonicalise app_user.username and app_user.email to lowercase so the
-- existing unique indexes can satisfy the new raw-equality lookups in
-- AppUserRepository (previously the IgnoreCase derived names forced
-- sequential scans via UPPER(col) = UPPER(?)).
--
-- Safety: today's pre-insert existsByUsernameIgnoreCase / existsByEmailIgnoreCase
-- checks have already prevented case-collision rows, so these UPDATEs cannot
-- violate the unique constraints on app_user.username or app_user.email.

UPDATE app_user
SET    username = LOWER(username)
WHERE  username <> LOWER(username);

UPDATE app_user
SET    email = LOWER(email)
WHERE  email IS NOT NULL
   AND email <> LOWER(email);
