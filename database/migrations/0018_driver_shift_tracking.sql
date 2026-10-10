-- Track real driver shifts. Unavailable remains on-shift; offline closes the shift.

create table if not exists public.driver_shifts (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  driver_id uuid not null references public.drivers(id) on delete cascade,
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  created_at timestamptz not null default now()
);

create unique index if not exists driver_shifts_one_open_idx
  on public.driver_shifts(driver_id)
  where ended_at is null;

create index if not exists driver_shifts_recent_idx
  on public.driver_shifts(tenant_id, driver_id, started_at desc);

create or replace function public.set_driver_status_with_shift(
  p_tenant_id uuid,
  p_driver_id uuid,
  p_status text
)
returns jsonb
language plpgsql
security invoker
set search_path=public
as $$
declare
  v_driver public.drivers;
  v_status public.driver_status;
begin
  begin
    v_status := p_status::public.driver_status;
  exception when others then
    raise exception 'Invalid driver status';
  end;

  select * into v_driver
  from public.drivers
  where id=p_driver_id and tenant_id=p_tenant_id
  for update;
  if not found then raise exception 'Driver not found'; end if;

  if v_status = 'offline' then
    update public.driver_shifts
      set ended_at=coalesce(ended_at,now())
      where tenant_id=p_tenant_id and driver_id=p_driver_id and ended_at is null;
  else
    if not exists (
      select 1 from public.driver_shifts
      where tenant_id=p_tenant_id and driver_id=p_driver_id and ended_at is null
    ) then
      insert into public.driver_shifts(tenant_id,driver_id,started_at)
      values(p_tenant_id,p_driver_id,now());
    end if;
  end if;

  update public.drivers
  set status=v_status,
      available_since=case when v_status='available' then now() else null end,
      last_seen_at=now(),
      updated_at=now()
  where id=p_driver_id and tenant_id=p_tenant_id;

  select * into v_driver from public.drivers where id=p_driver_id;
  return to_jsonb(v_driver);
end $$;

revoke all on public.driver_shifts from anon,authenticated;
grant all on public.driver_shifts to service_role;

alter table public.driver_shifts enable row level security;
create policy driver_shifts_authorized_select on public.driver_shifts
  for select to authenticated
  using (
    exists(select 1 from public.memberships m where m.tenant_id=driver_shifts.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher'))
    or exists(select 1 from public.drivers d where d.id=driver_shifts.driver_id and d.auth_user_id=(select auth.uid()))
  );
grant select on public.driver_shifts to authenticated;

revoke all on function public.set_driver_status_with_shift(uuid,uuid,text) from public,anon,authenticated;
grant execute on function public.set_driver_status_with_shift(uuid,uuid,text) to service_role;
