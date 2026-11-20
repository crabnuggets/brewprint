-- Apps a user connects through the OAuth server, such as Claude, may read that user's records but never change them.
-- Their access tokens carry a client_id claim; the Brewprint app's own sessions don't.

drop policy "Users add their own records" on public.records;
create policy "Users add their own records" on public.records
  for insert to authenticated
  with check ((select auth.uid()) = user_id and (select auth.jwt() ->> 'client_id') is null);

drop policy "Users change their own records" on public.records;
create policy "Users change their own records" on public.records
  for update to authenticated
  using ((select auth.uid()) = user_id and (select auth.jwt() ->> 'client_id') is null)
  with check ((select auth.uid()) = user_id and (select auth.jwt() ->> 'client_id') is null);
