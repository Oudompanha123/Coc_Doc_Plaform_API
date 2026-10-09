-- Coc Docs Platform -- Legal pages: revert Privacy to the highlights/CTA shape

-- WHY
-- V74 reseeded BOTH slugs with the new plain-document shape (title/subtitle/
-- sections/endNote/lastUpdated/updatedBy). That broke two things sharing
-- this same row that still expect the old shape: the public `/privacy` page
-- (`LegalPageLayout.tsx` does `content.highlights.map(...)` with no guard)
-- and the existing admin editor's Privacy tab (`LegalPageDetailsForm` and
-- friends). The task's own scope note ("do not touch the public legal
-- pages, their components, or their data source") and its Acceptance
-- Criterion 8 ("Public /terms + /privacy are unchanged, zero diff") rule out
-- fixing this by updating the public page instead -- so Privacy's stored
-- content goes back to exactly what V72 + V73 left it as.
--
-- Terms keeps the new shape V74 gave it: it has no public page (deleted
-- earlier this project), so only the new Step 3 admin editor reads it.
-- Going forward, the new document-shaped admin editor manages Terms only;
-- Privacy keeps its existing editor and public page completely unchanged.
--
-- // req: ADR-010 (fit check), ADR-012 §3

update legal_page_version
set content = '{
  "title": "Privacy Policy",
  "lead": "What we collect, why, and the choices you have.",
  "eyebrow": "Coc Docs Platform · Legal",
  "lastUpdated": "2026-10-01",
  "status": "draft",
  "bulletStyle": "check",
  "highlights": [
    { "icon": "eye", "title": "Only what''s needed", "text": "Name, email, and your activity on the platform." },
    { "icon": "lock", "title": "Kept safe", "text": "Passwords are hashed. Access is limited by role." },
    { "icon": "shield", "title": "Never sold", "text": "We don''t sell or share your data for ads." }
  ],
  "plainLanguage": "We collect what we need to sign you in and run the platform. We don''t sell anything, and there are no advertising trackers on this site.",
  "sections": [
    {
      "id": "information-we-collect", "title": "Information we collect", "icon": "database",
      "body": "- Account details: full name, email, and password (stored hashed).\n- Activity: sign-ins, sessions, and documents you view or edit.\n- HRMS attendance — only if an admin links your account."
    },
    {
      "id": "how-we-use-it", "title": "How we use it", "icon": "server",
      "body": "To run the platform: sign you in, show your documents and usage, and keep your account secure."
    },
    {
      "id": "who-can-see-your-data", "title": "Who can see your data", "icon": "eye",
      "body": "Admins can see account details and activity needed to manage the team. Other users can''t see your attendance."
    },
    {
      "id": "how-long-we-keep-it", "title": "How long we keep it", "icon": "history",
      "body": "We keep your data while your account is active, and delete or anonymise it after it''s no longer needed."
    },
    {
      "id": "your-choices", "title": "Your choices", "icon": "sliders",
      "body": "You can update your name, change notification settings, and sign out of other devices in Settings."
    }
  ],
  "cta": { "title": "Have a question?", "text": "Our team is happy to explain anything on this page." }
}'::jsonb
where slug = 'privacy';
