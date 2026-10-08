-- Coc Docs Platform -- Legal pages: rich text (Tiptap JSON) + new hero/CTA fields

-- WHY
-- The admin editor is moving from a plain paragraph/bullets toggle to a
-- Tiptap-based rich text editor (one instance per section + the new
-- "plain language" summary box), and the public page is being rebuilt to
-- match the approved mockup: a hero illustration + "N min read" chip, the
-- plain-language box, a per-section icon tile, and a bottom CTA box.
--
-- `content` stays `jsonb` with the same object-vs-array shape check
-- (legal_page_version_content_is_object) -- only the object's own fields
-- change:
--   - `sections[].kind`/`paragraph`/`bullets` -> `sections[].icon` + a single
--     `sections[].body` Tiptap JSON document (a Tiptap doc already mixes
--     paragraphs and bullet lists freely, so the old kind toggle is gone).
--   - new `plainLanguage` (Tiptap JSON), `cta` ({title, text}), and
--     `bulletStyle` ("circle" | "check", applied to every bullet list when
--     rendering).
--
-- This replaces every existing row's content outright rather than
-- transforming the old JSON in place: there are exactly four rows (two
-- slugs, draft + published) and all four currently hold the same seed copy,
-- so a literal replacement is simpler and less error-prone than JSON
-- surgery for a one-time, fully-known migration.
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
  "plainLanguage": {
    "type": "doc",
    "content": [
      { "type": "paragraph", "content": [{ "type": "text", "text": "You need an approved account, your organisation owns what you publish, and every change is recorded. Ticking the agreement box on the registration form accepts this document and the Privacy Policy." }] }
    ]
  },
  "sections": [
    {
      "id": "your-account", "title": "Your account", "icon": "person",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "Register with your work email, confirm the code we send, then wait for an admin to approve the account. One account per person — don''t share your password, and tell an admin if you think it has been exposed." }] }
      ] }
    },
    {
      "id": "acceptable-use", "title": "Acceptable use", "icon": "shieldCheck",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "Use the platform for your organisation''s documentation. Please don''t:" }] },
        { "type": "bulletList", "content": [
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "store live secrets — passwords, API keys, certificates — in page content;" }] }] },
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "upload material you have no right to share;" }] }] },
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "reach for content, settings or accounts your role doesn''t grant you." }] }] }
        ] }
      ] }
    },
    {
      "id": "your-content", "title": "Your content", "icon": "document",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "Your organisation owns the documentation you create. We store and serve it so the platform works, and we never use it to train models." }] }
      ] }
    },
    {
      "id": "roles-and-admins", "title": "Roles and admins", "icon": "key",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "What you can see and change comes from the role on your account, checked on the server for every request. Admins can approve, suspend, or remove an account; the pages you wrote stay with the organisation." }] }
      ] }
    },
    {
      "id": "service-and-changes", "title": "Service and changes", "icon": "sliders",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "We aim to keep the platform available during working hours, but can''t promise uninterrupted service. If these terms change in a way that matters, we''ll update the date above and tell you at your next sign-in." }] }
      ] }
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
  "plainLanguage": {
    "type": "doc",
    "content": [
      { "type": "paragraph", "content": [{ "type": "text", "text": "We collect what we need to sign you in and run the platform. We don''t sell anything, and there are no advertising trackers on this site." }] }
    ]
  },
  "sections": [
    {
      "id": "information-we-collect", "title": "Information we collect", "icon": "database",
      "body": { "type": "doc", "content": [
        { "type": "bulletList", "content": [
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Account details: full name, email, and password (stored hashed)." }] }] },
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Activity: sign-ins, sessions, and documents you view or edit." }] }] },
          { "type": "listItem", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "HRMS attendance — only if an admin links your account." }] }] }
        ] }
      ] }
    },
    {
      "id": "how-we-use-it", "title": "How we use it", "icon": "server",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "To run the platform: sign you in, show your documents and usage, and keep your account secure." }] }
      ] }
    },
    {
      "id": "who-can-see-your-data", "title": "Who can see your data", "icon": "eye",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "Admins can see account details and activity needed to manage the team. Other users can''t see your attendance." }] }
      ] }
    },
    {
      "id": "how-long-we-keep-it", "title": "How long we keep it", "icon": "history",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "We keep your data while your account is active, and delete or anonymise it after it''s no longer needed." }] }
      ] }
    },
    {
      "id": "your-choices", "title": "Your choices", "icon": "sliders",
      "body": { "type": "doc", "content": [
        { "type": "paragraph", "content": [{ "type": "text", "text": "You can update your name, change notification settings, and sign out of other devices in Settings." }] }
      ] }
    }
  ],
  "cta": { "title": "Have a question?", "text": "Our team is happy to explain anything on this page." }
}'::jsonb
where slug = 'privacy';
