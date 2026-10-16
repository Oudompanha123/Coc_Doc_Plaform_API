-- Coc Docs Platform -- Store: an app is a Mobile app or a PC app (ADR-112)
--
-- WHY
-- "Add App" now asks whether the app is for Mobile or PC. The answer decides
-- which listing tab the app is under -- even before it has a build -- which
-- platform tabs its page shows (iOS / Android, or Windows / macOS), and which
-- files "Next version" accepts (.apk/.aab/.ipa, or .exe/.msi/.msix/.appx/
-- .dmg/.pkg). Within its category an app still spans platforms (ADR-111).
--
-- Existing apps get a category from their builds: 'pc' when every build they
-- have ever had is Windows or macOS, 'mobile' otherwise (including apps with
-- no builds yet -- an admin can change that on Edit App info while the app
-- has no builds). No build is touched.
--
-- V85 is not edited: it may already be applied.
--
-- // req: ADR-112

alter table store_app add column category text;

update store_app a
set category = case
  when exists (select 1 from store_build b where b.app_id = a.id)
   and not exists (select 1 from store_build b where b.app_id = a.id and b.platform in ('android', 'ios'))
  then 'pc'
  else 'mobile'
end;

alter table store_app alter column category set not null;
alter table store_app alter column category set default 'mobile';
alter table store_app add constraint store_app_category_check check (category in ('mobile', 'pc'));

comment on column store_app.category is
  'Mobile (iOS / Android builds) or PC (Windows / macOS builds): the listing tab, the page''s platform tabs, and the files Next version accepts (ADR-112).';
