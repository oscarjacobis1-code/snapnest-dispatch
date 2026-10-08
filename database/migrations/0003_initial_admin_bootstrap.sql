-- One-time initial administrator bootstrap.
-- The production bootstrap code hash is data and is intentionally NOT committed here.

create table if not exists public.system_bootstrap (
  key text primary key,
  code_hash text not null,
  claimed_at timestamptz,
  claimed_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint system_bootstrap_key_check check (key = 'initial_admin')
);

alter table public.system_bootstrap enable row level security;
revoke all on public.system_bootstrap from anon, authenticated;
grant all on public.system_bootstrap to service_role;

create or replace function public.claim_initial_admin(
  p_user_id uuid,
  p_code_hash text
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
  v_bootstrap public.system_bootstrap;
  v_tenant_id uuid;
  v_members integer;
begin
  select * into v_bootstrap
  from public.system_bootstrap
  where key = 'initial_admin'
  for update;

  if not found then
    raise exception 'Bootstrap is not configured';
  end if;

  if v_bootstrap.claimed_at is not null then
    raise exception 'Bootstrap is already complete';
  end if;

  if v_bootstrap.code_hash <> p_code_hash then
    raise exception 'Invalid bootstrap code';
  end if;

  select count(*) into v_members from public.memberships;
  if v_members > 0 then
    raise exception 'Bootstrap is locked because a member already exists';
  end if;

  select id into v_tenant_id
  from public.tenants
  where slug = 'demo-base'
  limit 1;

  if v_tenant_id is null then
    raise exception 'Bootstrap tenant not found';
  end if;

  insert into public.memberships(tenant_id, user_id, role)
  values(v_tenant_id, p_user_id, 'admin');

  update public.system_bootstrap
  set claimed_at = now(), claimed_by = p_user_id, updated_at = now()
  where key = 'initial_admin';

  return jsonb_build_object(
    'ok', true,
    'tenant_id', v_tenant_id,
    'role', 'admin'
  );
end
$$;

revoke all on function public.claim_initial_admin(uuid,text) from public, anon, authenticated;
grant execute on function public.claim_initial_admin(uuid,text) to service_role;
