-- Remove all Claude Usage roster entries that were auto-created or are no
-- longer needed. Keeps only the admin account (sophannasam25@gmail.com).
-- Daily usage history rows are cascade-deleted with their member row.
delete from claude_usage_member
where email != 'sophannasam25@gmail.com';
