-- Beans and recipes Claude drafts for the user, waiting in the app's Library to be added or discarded.
-- Apps connected through the OAuth server, such as Claude, may write here; records stays read-only to them.
-- Adding a draft in the app saves it as a library item with the draft's id and deletes the draft.
create table public.drafts (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id text not null,
  kind text not null check (kind in ('beans', 'recipes')),
  data jsonb not null,
  created_at timestamptz not null default now(),
  primary key (user_id, id)
);

alter table public.drafts enable row level security;

create policy "Users manage their own drafts" on public.drafts
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

revoke all on public.drafts from anon;
grant select, insert, update, delete on public.drafts to authenticated;
