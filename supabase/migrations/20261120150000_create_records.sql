-- One row per synced item (beans, grinder, recipe or brew record) for each user.
create table public.records (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null check (kind in ('beans', 'grinders', 'recipes', 'brews')),
  id text not null,
  data jsonb,
  deleted boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (user_id, kind, id)
);

create index records_user_updated on public.records (user_id, updated_at);

-- The server sets updated_at, so device clocks never decide what is newer.
create function public.records_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger records_touch before insert or update on public.records
for each row execute function public.records_touch();

alter table public.records enable row level security;

create policy "Users read their own records" on public.records
  for select to authenticated using ((select auth.uid()) = user_id);
create policy "Users add their own records" on public.records
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "Users change their own records" on public.records
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

revoke all on public.records from anon;
grant select, insert, update on public.records to authenticated;
