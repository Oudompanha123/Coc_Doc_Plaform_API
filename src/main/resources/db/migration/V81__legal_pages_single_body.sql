-- Coc Docs Platform -- Legal pages: one Markdown body per page

-- WHY
-- Product owner, 2026-10-14: "Legal pages just one text editor, should not
-- [have] section[s], because the user can do sections in the text editor."
-- Both slugs move to one shape:
--
--   { "title", "subtitle", "lastUpdated", "updatedBy", "body" }
--
-- `body` is Markdown in the same whitelisted dialect as before (markdown.ts /
-- MarkdownValidator), now with `##` section headings: a heading is where a
-- section starts, and the public page builds its table of contents from them.
-- Privacy's highlight cards, plain-language box and CTA go (the product
-- owner chose "both pages" over keeping them), and so do Terms' per-section
-- list, end note and draft/approved status flag. See ADR-104 r2.13.
--
-- Transformed in place, not reseeded like V71-V75: those ran while every row
-- still held the seed, but editors have saved real drafts and published
-- versions since. Every row is converted, archived ones included, so History
-- can still preview and restore them. Nothing is dropped silently -- each
-- old field becomes Markdown in the body:
--
--   plainLanguage    -> a "> " quote at the top
--   highlights[]     -> a bullet list of "**title** — text"
--   sections[]       -> "## title" followed by its body, in order
--   cta              -> a final "## title" section with its text
--   endNote          -> after a "---" rule at the end
--   lead (Privacy)   -> subtitle
--
-- // req: ADR-010 (fit check), ADR-012 §3

update legal_page_version v
set content = jsonb_build_object(
  'title',       coalesce(v.content->>'title', ''),
  'subtitle',    coalesce(nullif(v.content->>'subtitle', ''), v.content->>'lead', ''),
  'lastUpdated', coalesce(v.content->>'lastUpdated', ''),
  'updatedBy',   coalesce(nullif(v.content->>'updatedBy', ''), 'the Platform Team'),
  'body',        coalesce(nullif(concat_ws(E'\n\n',
    -- plainLanguage, quoted line by line
    case when coalesce(btrim(v.content->>'plainLanguage'), '') <> ''
         then '> ' || replace(btrim(v.content->>'plainLanguage'), E'\n', E'\n> ') end,
    -- highlight cards
    (select string_agg('- **' || btrim(h->>'title') || '** — ' || btrim(h->>'text'), E'\n' order by n)
       from jsonb_array_elements(case when jsonb_typeof(v.content->'highlights') = 'array'
                                      then v.content->'highlights' else '[]'::jsonb end)
            with ordinality as hs(h, n)
      where coalesce(btrim(h->>'title'), '') <> '' or coalesce(btrim(h->>'text'), '') <> ''),
    -- sections, in order
    (select string_agg(concat_ws(E'\n\n', '## ' || btrim(s->>'title'), nullif(btrim(s->>'body'), '')), E'\n\n' order by n)
       from jsonb_array_elements(case when jsonb_typeof(v.content->'sections') = 'array'
                                      then v.content->'sections' else '[]'::jsonb end)
            with ordinality as ss(s, n)),
    -- CTA, as a closing section
    case when coalesce(btrim(v.content->'cta'->>'title'), '') <> ''
         then concat_ws(E'\n\n', '## ' || btrim(v.content->'cta'->>'title'), nullif(btrim(v.content->'cta'->>'text'), '')) end,
    -- end note
    case when coalesce(btrim(v.content->>'endNote'), '') <> ''
         then E'---\n\n' || btrim(v.content->>'endNote') end
  ), ''), '')
)
where not (v.content ? 'body');
