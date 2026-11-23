-- Claude may also draft tasting notes for a brew: a row of kind 'journals' whose id is the brew's id and whose data is
-- the brew's journal as Claude proposes it. The app shows it on that brew; saving or discarding it deletes the row.
-- App versions that don't know the kind ignore it.
alter table public.drafts drop constraint drafts_kind_check;
alter table public.drafts add constraint drafts_kind_check check (kind in ('beans', 'recipes', 'journals'));
