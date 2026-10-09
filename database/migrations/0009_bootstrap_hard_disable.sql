create or replace function public.claim_initial_admin(p_user_id uuid,p_code_hash text)
returns jsonb
language plpgsql
security definer
set search_path=public
as $$
begin
  raise exception 'Bootstrap is permanently disabled';
end $$;

revoke all on function public.claim_initial_admin(uuid,text) from public,anon,authenticated;
grant execute on function public.claim_initial_admin(uuid,text) to service_role;
