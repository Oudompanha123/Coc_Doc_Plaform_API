-- Coc Docs Platform -- Legal pages: admin-editable Terms of Service / Privacy Policy

-- WHY
-- Terms of Service and Privacy Policy need draft/publish/history the same way
-- page_version gives every other page (ADR-003) -- but page_version.body is
-- constrained to the ADR-010 block-array vocabulary
-- (check (jsonb_typeof(body) = 'array')), and a legal page's content is an
-- OBJECT (title/lead/lastUpdated/status/highlights/sections), not a block
-- array. Forcing it into that shape would mean loosening an existing
-- migration's constraint, which is not allowed -- so this is a new table
-- that copies page_version's proven pattern instead: partial unique indexes
-- enforcing at most one draft and one published row per slug, version_no for
-- optimistic locking, and publishing by archiving the old published row
-- rather than deleting it -- so "published versions" history is free, the
-- same way it is for page_version.
--
-- Only two slugs exist today (terms, privacy); a check constraint, not a
-- lookup table, keeps that simple while still leaving room for a third legal
-- page later without a migration that touches this one.
--
-- // req: ADR-003

create table legal_page_version (
  id uuid primary key default gen_random_uuid(),
  slug text not null check (slug in ('terms', 'privacy')),
  status text not null default 'draft' check (status in ('draft', 'published', 'archived')),
  content jsonb not null,
  version_no integer not null default 1,
  published_at timestamptz,
  published_by uuid references account(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint legal_page_version_content_is_object check (jsonb_typeof(content) = 'object')
);

create unique index legal_page_version_one_published_idx on legal_page_version (slug) where status = 'published';
create unique index legal_page_version_one_draft_idx     on legal_page_version (slug) where status = 'draft';
create index legal_page_version_lookup_idx on legal_page_version (slug, status);

comment on table legal_page_version is
  'Terms of Service / Privacy Policy content, versioned like page_version (ADR-003) but with an object-shaped content column instead of a block array.';

-- Seed: one draft + one published row per slug, identical content, so a
-- fresh DB renders /terms and /privacy immediately and the admin editor opens
-- to a draft that matches what is already live (no phantom "unsaved changes"
-- on first load). published_by is NULL -- no account exists yet on a fresh
-- database -- and the public page/admin history treat a NULL publisher as
-- "system seeded" rather than attributing it to nobody in particular.
insert into legal_page_version (slug, status, content, version_no, published_at, published_by)
values
  (
    'terms',
    'published',
    '{
      "title": "Terms of Service",
      "lead": "The simple rules for using Coc Docs Platform.",
      "lastUpdated": "2026-10-01",
      "status": "draft",
      "highlights": [
        { "icon": "person", "title": "Your account", "text": "Keep your login safe. You''re responsible for activity on it." },
        { "icon": "document", "title": "Your work", "text": "Use the platform for your team''s documentation only." },
        { "icon": "ban", "title": "Fair use", "text": "Don''t access pages or data your role doesn''t allow." }
      ],
      "sections": [
        { "id": "accepting-these-terms", "title": "Accepting these terms", "kind": "paragraph",
          "paragraph": "By creating an account or signing in, you agree to these terms. If you don''t agree, please don''t use the platform." },
        { "id": "your-account", "title": "Your account", "kind": "bullets",
          "bullets": ["Give a real name and a work email when you register.", "Keep your password private and use a strong one.", "Admins can change your role or deactivate your account."] },
        { "id": "using-the-platform", "title": "Using the platform", "kind": "paragraph",
          "paragraph": "Use Coc Docs Platform only for your work. Editing is limited by your role — ask an admin if you need more access." },
        { "id": "content-you-create", "title": "Content you create", "kind": "paragraph",
          "paragraph": "Documents you write belong to your organization. Don''t upload content you don''t have the right to share." },
        { "id": "changes-to-these-terms", "title": "Changes to these terms", "kind": "paragraph",
          "paragraph": "We may update these terms. We''ll show the new date at the top and tell you about important changes." }
      ]
    }'::jsonb,
    1,
    now(),
    null
  ),
  (
    'privacy',
    'published',
    '{
      "title": "Privacy Policy",
      "lead": "What we collect, why, and the choices you have.",
      "lastUpdated": "2026-10-01",
      "status": "draft",
      "highlights": [
        { "icon": "eye", "title": "Only what''s needed", "text": "Name, email, and your activity on the platform." },
        { "icon": "lock", "title": "Kept safe", "text": "Passwords are hashed. Access is limited by role." },
        { "icon": "heart", "title": "Never sold", "text": "We don''t sell or share your data for ads." }
      ],
      "sections": [
        { "id": "information-we-collect", "title": "Information we collect", "kind": "bullets",
          "bullets": ["Account details: full name, email, and password (stored hashed).", "Activity: sign-ins, sessions, and documents you view or edit.", "HRMS attendance — only if an admin links your account."] },
        { "id": "how-we-use-it", "title": "How we use it", "kind": "paragraph",
          "paragraph": "To run the platform: sign you in, show your documents and usage, and keep your account secure." },
        { "id": "who-can-see-your-data", "title": "Who can see your data", "kind": "paragraph",
          "paragraph": "Admins can see account details and activity needed to manage the team. Other users can''t see your attendance." },
        { "id": "how-long-we-keep-it", "title": "How long we keep it", "kind": "paragraph",
          "paragraph": "We keep your data while your account is active, and delete or anonymize it after it''s no longer needed." },
        { "id": "your-choices", "title": "Your choices", "kind": "paragraph",
          "paragraph": "You can update your name, change notification settings, and sign out of other devices in Settings." }
      ]
    }'::jsonb,
    1,
    now(),
    null
  );

insert into legal_page_version (slug, status, content, version_no, published_at, published_by)
select slug, 'draft', content, version_no, null, null
from legal_page_version
where status = 'published';
