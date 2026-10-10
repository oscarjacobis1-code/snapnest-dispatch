-- Drivers only read their own profile. Operators can read their tenant fleet.
-- The earlier policy admitted every tenant member, including drivers, to every
-- driver's profile and phone number through the Data API.
drop policy if exists drivers_member_select on public.drivers;

create policy drivers_member_select on public.drivers
for select to authenticated
using (
  auth_user_id = (select auth.uid())
  or exists (
    select 1 from public.memberships m
    where m.tenant_id = drivers.tenant_id
      and m.user_id = (select auth.uid())
      and m.role in ('admin', 'dispatcher')
  )
);
