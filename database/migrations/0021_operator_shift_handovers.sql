-- Track dispatcher/admin shifts and explicit handover notes.
create table if not exists public.operator_shifts (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('admin','dispatcher')),
  started_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  ended_at timestamptz,
  handover_note text,
  handover_snapshot jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create unique index if not exists operator_shifts_one_open_idx
  on public.operator_shifts(tenant_id,user_id)
  where ended_at is null;

create index if not exists operator_shifts_recent_idx
  on public.operator_shifts(tenant_id,started_at desc);

alter table public.operator_shifts enable row level security;

create policy operator_shifts_dispatch_select on public.operator_shifts
  for select to authenticated
  using (
    exists(
      select 1 from public.memberships m
      where m.tenant_id=operator_shifts.tenant_id
        and m.user_id=(select auth.uid())
        and m.role in ('admin','dispatcher')
    )
  );

revoke all on public.operator_shifts from anon,authenticated;
grant select on public.operator_shifts to authenticated;
grant all on public.operator_shifts to service_role;
