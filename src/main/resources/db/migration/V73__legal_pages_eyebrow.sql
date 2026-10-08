-- Coc Docs Platform -- Legal pages: add the hero eyebrow field

-- WHY
-- The admin form mockup (task spec, Step 3) has a "Hero eyebrow" field --
-- the small pill above the title ("Coc Docs Platform · Legal") -- as its
-- own editable group, not hardcoded in the layout component as it was
-- through V71/V72. Adds `eyebrow` to every existing row via `jsonb_set`
-- rather than replacing the whole content object, since every other field
-- is unchanged.
--
-- // req: ADR-003

update legal_page_version
set content = jsonb_set(content, '{eyebrow}', '"Coc Docs Platform · Legal"'::jsonb);
