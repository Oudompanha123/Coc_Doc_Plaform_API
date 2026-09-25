-- Remove the pricing-smoketest account that was auto-created during smoke
-- testing before auto-creation was disabled. Hard delete is safe here: this
-- email has no real usage history (0 tokens, never active) and was never
-- added by an admin.
delete from claude_usage_member
where email = 'pricing-smoketest@example.com';
