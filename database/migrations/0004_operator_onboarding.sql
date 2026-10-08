create or replace function public.onboard_tenant_user(
  p_tenant_id uuid,
  p_user_id uuid,
  p_role public.tenant_role,
  p_display_name text default null,
  p_phone_e164 text default null,
  p_vehicle_plate text default null
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
  v_driver_id uuid;
begin
  if p_role not in ('admin','dispatcher','driver') then
    raise exception 'Invalid tenant role';
  end if;

  if not exists (select 1 from public.tenants where id = p_tenant_id) then
    raise exception 'Tenant not found';
  end if;

  insert into public.memberships(tenant_id, user_id, role)
  values(p_tenant_id, p_user_id, p_role);

  if p_role = 'driver' then
    if coalesce(trim(p_display_name), '') = '' then
      raise exception 'Driver display name is required';
    end if;

    insert into public.drivers(
      tenant_id, auth_user_id, display_name, phone_e164, vehicle_plate,
      status, queue_rank, recent_declines
    )
    values(
      p_tenant_id, p_user_id, trim(p_display_name), nullif(trim(p_phone_e164), ''),
      nullif(trim(p_vehicle_plate), ''), 'offline', 0, 0
    )
    returning id into v_driver_id;
  end if;

  return jsonb_build_object(
    'ok', true,
    'tenant_id', p_tenant_id,
    'user_id', p_user_id,
    'role', p_role,
    'driver_id', v_driver_id
  );
end
$$;

revoke all on function public.onboard_tenant_user(uuid,uuid,public.tenant_role,text,text,text) from public, anon, authenticated;
grant execute on function public.onboard_tenant_user(uuid,uuid,public.tenant_role,text,text,text) to service_role;
