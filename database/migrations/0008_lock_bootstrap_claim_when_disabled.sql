create or replace function public.claim_initial_admin(p_user_id uuid,p_code_hash text)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
declare v_row public.system_bootstrap; v_tenant uuid;
begin
  select * into v_row from public.system_bootstrap where key='initial_admin' for update;
  if not found then raise exception 'Bootstrap is not configured'; end if;
  if v_row.disabled_at is not null then raise exception 'Bootstrap is disabled'; end if;
  if v_row.claimed_at is not null then raise exception 'Bootstrap already claimed'; end if;
  if v_row.code_hash <> p_code_hash then raise exception 'Invalid bootstrap code'; end if;
  if exists(select 1 from public.memberships) then raise exception 'A tenant member already exists'; end if;
  select id into v_tenant from public.tenants where slug='demo-base' limit 1;
  if v_tenant is null then raise exception 'Demo tenant not found'; end if;
  insert into public.memberships(tenant_id,user_id,role) values(v_tenant,p_user_id,'admin');
  update public.system_bootstrap set claimed_at=now(),claimed_by=p_user_id where key='initial_admin';
  return jsonb_build_object('ok',true,'tenant_id',v_tenant,'role','admin');
end $$;

revoke all on function public.claim_initial_admin(uuid,text) from public,anon,authenticated;
grant execute on function public.claim_initial_admin(uuid,text) to service_role;
