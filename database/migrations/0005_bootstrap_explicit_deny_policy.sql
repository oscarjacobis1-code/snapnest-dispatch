create policy system_bootstrap_no_client_access
on public.system_bootstrap
for all
to anon, authenticated
using (false)
with check (false);
