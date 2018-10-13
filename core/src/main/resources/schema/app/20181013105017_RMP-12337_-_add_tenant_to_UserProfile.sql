-- // RMP-12337 - add tenant to UserProfile
-- Migration SQL that makes the change goes here.

ALTER TABLE userprofile DROP CONSTRAINT IF EXISTS uk_userprofile_account_name;
ALTER TABLE userprofile
 ADD CONSTRAINT uk_userprofile_userid UNIQUE (user_id);

ALTER TABLE users DROP CONSTRAINT users_userid_is_unique;
ALTER TABLE users
 ADD CONSTRAINT users_userid_tenantid_is_unique UNIQUE (userid, tenant_id);

ALTER TABLE users DROP CONSTRAINT users_email_key;

drop index users_userid_idx;

-- //@UNDO
-- SQL to undo the change goes here.

ALTER TABLE userprofile DROP CONSTRAINT IF EXISTS uk_userprofile_userid;
ALTER TABLE userprofile
 ADD CONSTRAINT uk_userprofile_account_name UNIQUE (account, owner);

ALTER TABLE users DROP CONSTRAINT users_userid_tenantid_is_unique;
ALTER TABLE users
 ADD CONSTRAINT users_userid_is_unique UNIQUE (userid);

create unique index users_userid_idx
  on users (userid);