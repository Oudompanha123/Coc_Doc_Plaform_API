-- Coc Docs Platform -- Legal pages: Markdown instead of Tiptap JSON

-- WHY
-- V71 moved the plain-language box and every section body from plain
-- strings to Tiptap JSON, with a Tiptap-based editor planned for the admin
-- side. That plan was reversed before the editor was built: the product
-- decision is now a hand-written Markdown editor (toolbar + write/preview/
-- side-by-side/fullscreen), with no new dependency for the editor or the
-- renderer. `plainLanguage` and `sections[].body` go back to plain strings
-- -- this time holding the small whitelisted Markdown dialect `markdown.ts`
-- parses (paragraphs, ###/#### headings, **bold**, *italic*, -/1. lists two
-- levels deep, > quotes, --- dividers, [text](url) links restricted to
-- https:///mailto:) instead of either free-form prose or Tiptap's node tree.
--
-- Same reasoning as V71 for replacing content outright rather than
-- transforming JSON in place: there are exactly four rows (two slugs, draft
-- + published), all four currently hold the same Tiptap JSON seed, so a
-- literal replacement with the equivalent Markdown is simpler and less
-- error-prone than a JSON-to-Markdown conversion for a one-time, fully-known
-- migration.
--
-- // req: ADR-003, ADR-012 §3

update legal_page_version
set content = '{
  "title": "Terms of Service",
  "lead": "What you agree to when you use the platform.",
  "lastUpdated": "2026-10-01",
  "status": "draft",
  "bulletStyle": "circle",
  "highlights": [
    { "icon": "person", "title": "Approved accounts", "text": "Register, confirm your email, then an admin approves you." },
    { "icon": "document", "title": "Your content stays yours", "text": "Your organisation owns every page you publish." },
    { "icon": "history", "title": "Every change is recorded", "text": "Versions and an audit trail, kept for at least a year." }
  ],
  "plainLanguage": "You need an approved account, your organisation owns what you publish, and every change is recorded. Ticking the agreement box on the registration form accepts this document and the Privacy Policy.",
  "sections": [
    {
      "id": "your-account", "title": "Your account", "icon": "person",
      "body": "Register with your work email, confirm the code we send, then wait for an admin to approve the account. One account per person — don''t share your password, and tell an admin if you think it has been exposed."
    },
    {
      "id": "acceptable-use", "title": "Acceptable use", "icon": "shieldCheck",
      "body": "Use the platform for your organisation''s documentation. **Please don''t:**\n\n- store live secrets — passwords, API keys, certificates — in page content;\n- upload material you have no right to share;\n- reach for content, settings or accounts your role doesn''t grant you."
    },
    {
      "id": "your-content", "title": "Your content", "icon": "document",
      "body": "Your organisation owns the documentation you create. We store and serve it so the platform works, and we never use it to train models."
    },
    {
      "id": "roles-and-admins", "title": "Roles and admins", "icon": "key",
      "body": "What you can see and change comes from the role on your account, checked on the server for every request. Admins can approve, suspend, or remove an account; the pages you wrote stay with the organisation."
    },
    {
      "id": "service-and-changes", "title": "Service and changes", "icon": "sliders",
      "body": "We aim to keep the platform available during working hours, but can''t promise uninterrupted service. If these terms change in a way that matters, we''ll update the date above and tell you at your next sign-in."
    }
  ],
  "cta": { "title": "Have a question?", "text": "Our team is happy to explain anything on this page." }
}'::jsonb
where slug = 'terms';

update legal_page_version
set content = '{
  "title": "Privacy Policy",
  "lead": "What we collect, why, and the choices you have.",
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
