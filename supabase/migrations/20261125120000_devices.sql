-- Phones that show a notification when the user's drafts change (docs/adr/0006), one row per Firebase Cloud Messaging
-- token. The app's own sessions add and remove their user's rows; apps connected through the OAuth server, such as
-- Claude, can neither read nor change them. brewprint-mcp reads the tokens with the service role to send the message.
create table public.devices (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  token text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, token)
);

alter table public.devices enable row level security;

create policy "The app manages its user's devices" on public.devices
  for all to authenticated
  using ((select auth.uid()) = user_id and (select auth.jwt() ->> 'client_id') is null)
  with check ((select auth.uid()) = user_id and (select auth.jwt() ->> 'client_id') is null);

revoke all on public.devices from anon;
grant select, insert, update, delete on public.devices to authenticated;
