-- Coc Docs Platform -- Legal pages: plain-document content shape

-- WHY
-- Task "Admin 'Legal pages' editor ONLY (Apple-style document, Markdown
-- editor)" replaces the highlights/eyebrow/plain-language/CTA field set
-- (V71-V73) with a plain document: title, subtitle, an ordered list of
-- anchored sections, an end note, and a last-updated byline -- matching
-- "legal-document-content-mockup.png". `content` stays a jsonb object (ADR
-- fit unchanged, see V68); only the keys inside it change.
--
-- Same reasoning as V71/V72 for replacing content outright rather than
-- transforming JSON in place: there are exactly four rows (two slugs, draft
-- + published), all four currently hold the same highlights/CTA-shaped
-- seed, so a literal replacement is simpler and less error-prone than an
-- in-place JSON transform for a one-time, fully-known migration.
--
-- Terms of Service copy is the sample text from the task's own attached
-- mockup image, transcribed verbatim (9 sections, matching the task spec's
-- own worked example "count `9 sections`"). Privacy Policy copy carries
-- forward the six sections the task spec names in its "Seed" item,
-- reworded to drop the per-section icon field this shape no longer has.
--
-- // req: ADR-010 (fit check), ADR-012 §3

update legal_page_version
set content = '{
  "title": "Terms of Service",
  "subtitle": "Coc Docs Platform · Legal Information",
  "status": "draft",
  "sections": [
    {
      "id": "agreement-to-these-terms", "title": "Agreement to These Terms",
      "body": "These Terms of Service (the \"Terms\") apply to Coc Docs Platform and every page, tool, and service it provides (together, the \"Platform\"). The Platform is operated by your organisation. BY CREATING AN ACCOUNT OR USING THE PLATFORM, YOU AGREE TO THESE TERMS. IF YOU DO NOT AGREE, DO NOT USE THE PLATFORM.\n\nWe may change these Terms at any time. When we do, we update the date at the bottom of this page and tell you at your next sign-in. Using the Platform after a change means you accept the new Terms."
    },
    {
      "id": "your-account", "title": "Your Account",
      "body": "You need an approved account to use the Platform. Register with your work email, confirm the code we send you, then wait for an admin to approve the account.\n\nYou are responsible for keeping your password private and for all activity under your account. One account per person. If you think your password has been exposed, tell an admin right away."
    },
    {
      "id": "acceptable-use", "title": "Acceptable Use",
      "body": "Use the Platform only for your organisation''s documentation work. You agree that you will not:\n\n- store live secrets — passwords, API keys, or certificates — in page content;\n- upload material you have no right to share;\n- try to access content, settings, or accounts your role does not allow;\n- disrupt or overload the Platform, or try to get around its security."
    },
    {
      "id": "your-content", "title": "Your Content",
      "body": "Your organisation owns the documentation you create. We store and serve it so the Platform works. We never use your content to train models.\n\nEvery change is recorded. Versions and an audit trail are kept for at least one year."
    },
    {
      "id": "roles-and-admins", "title": "Roles and Admins",
      "body": "What you can see and change depends on the role on your account, and is checked on the server for every request. Admins can approve, suspend, or remove an account. Pages you wrote stay with the organisation."
    },
    {
      "id": "related-policies", "title": "Related Policies",
      "body": "The following policies also apply to your use of the Platform and are part of these Terms:\n\n- [Privacy Policy](/privacy)\n- Acceptable Use Guidelines\n- Security Contacts\n\nEach of these policies may change from time to time and applies from when it is posted."
    },
    {
      "id": "disclaimers", "title": "Disclaimers",
      "body": "THE PLATFORM IS PROVIDED \"AS IS\" AND \"AS AVAILABLE\". WE DO NOT PROMISE THAT THE PLATFORM WILL BE ERROR-FREE OR UNINTERRUPTED, OR THAT ANY CONTENT WILL BE ACCURATE OR UP TO DATE. YOU USE THE PLATFORM AT YOUR OWN RISK."
    },
    {
      "id": "service-and-changes", "title": "Service and Changes",
      "body": "We aim to keep the Platform available during working hours, but we can''t promise uninterrupted service. We may change, pause, or stop any part of the Platform at any time."
    },
    {
      "id": "contact", "title": "Contact",
      "body": "Questions about these Terms? Contact the platform team at [legal@example.com](mailto:legal@example.com)."
    }
  ],
  "endNote": "*The information on this page may change without notice.*\n\n*© 2026 Coc Docs Platform. All rights reserved.*",
  "lastUpdated": "2026-10-01",
  "updatedBy": "the Platform Team"
}'::jsonb
where slug = 'terms';

update legal_page_version
set content = '{
  "title": "Privacy Policy",
  "subtitle": "Coc Docs Platform · Legal Information",
  "status": "draft",
  "sections": [
    {
      "id": "information-we-collect", "title": "Information we collect",
      "body": "- Account details: full name, email, and password (stored hashed).\n- Activity: sign-ins, sessions, and documents you view or edit.\n- HRMS attendance — only if an admin links your account."
    },
    {
      "id": "how-we-use-it", "title": "How we use it",
      "body": "To run the Platform: sign you in, show your documents and usage, and keep your account secure."
    },
    {
      "id": "who-can-see-your-data", "title": "Who can see your data",
      "body": "Admins can see account details and activity needed to manage the team. Other users can''t see your attendance."
    },
    {
      "id": "how-long-we-keep-it", "title": "How long we keep it",
      "body": "We keep your data while your account is active, and delete or anonymise it after it''s no longer needed."
    },
    {
      "id": "your-choices", "title": "Your choices",
      "body": "You can update your name, change notification settings, and sign out of other devices in Settings."
    },
    {
      "id": "contact", "title": "Contact",
      "body": "Questions about this Privacy Policy? Contact the platform team at [legal@example.com](mailto:legal@example.com)."
    }
  ],
  "endNote": "*The information on this page may change without notice.*\n\n*© 2026 Coc Docs Platform. All rights reserved.*",
  "lastUpdated": "2026-10-01",
  "updatedBy": "the Platform Team"
}'::jsonb
where slug = 'privacy';
