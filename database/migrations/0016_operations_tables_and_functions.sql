-- SnapNest Dispatch operations modules.
-- Run after 0015 commits the scheduled/no_show booking status values.

alter table public.bookings
  add column if not exists scheduled_for timestamptz,
  add column if not exists cancelled_at timestamptz,
  add column if not exists cancellation_reason text,
  add column if not exists cancellation_code text,
  add column if not exists cancelled_by_role text,
  add column if not exists no_show_at timestamptz,
  add column if not exists reassign_count integer not null default 0;

create index if not exists bookings_scheduled_due_idx on public.bookings(tenant_id, scheduled_for) where status='scheduled';
create index if not exists bookings_driver_history_idx on public.bookings(tenant_id, assigned_driver_id, created_at desc);

create table if not exists public.customer_profiles (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  display_name text not null default 'Guest',
  phone_e164 text not null,
  notes text,
  total_bookings integer not null default 0,
  last_booking_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(tenant_id, phone_e164)
);
create index if not exists customer_profiles_tenant_recent_idx on public.customer_profiles(tenant_id, last_booking_at desc);

create table if not exists public.support_tickets (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  created_by_user_id uuid references auth.users(id) on delete set null,
  driver_id uuid references public.drivers(id) on delete set null,
  booking_id uuid references public.bookings(id) on delete set null,
  category text not null default 'app' check (category in ('app','dispatch','vehicle','payment','customer','safety','other')),
  priority text not null default 'normal' check (priority in ('low','normal','high','urgent')),
  subject text not null,
  description text not null,
  attachment_url text,
  status text not null default 'open' check (status in ('open','in_progress','resolved')),
  resolution_note text,
  resolved_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists support_tickets_tenant_status_idx on public.support_tickets(tenant_id, status, created_at desc);
create index if not exists support_tickets_driver_idx on public.support_tickets(driver_id, created_at desc);

create or replace function public.assign_booking_driver(p_tenant_id uuid,p_booking_id uuid,p_driver_id uuid)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_booking public.bookings; v_driver public.drivers; v_old_driver uuid;
begin
  select * into v_booking from public.bookings where id=p_booking_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status in ('completed','cancelled','no_show','in_progress') then raise exception 'Booking cannot be assigned in its current state'; end if;
  if v_booking.status='scheduled' and v_booking.scheduled_for is not null and v_booking.scheduled_for > now() then raise exception 'Scheduled booking is not active yet'; end if;
  select * into v_driver from public.drivers where id=p_driver_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Driver not found'; end if;
  if v_booking.assigned_driver_id is distinct from p_driver_id and v_driver.status <> 'available' then raise exception 'Driver is not available'; end if;
  v_old_driver := v_booking.assigned_driver_id;
  update public.dispatch_offers set response='expired',responded_at=coalesce(responded_at,now()) where tenant_id=p_tenant_id and booking_id=p_booking_id and response is null;
  if v_old_driver is not null and v_old_driver is distinct from p_driver_id then
    update public.drivers set status='available',available_since=now(),updated_at=now(),last_seen_at=coalesce(last_seen_at,now()) where id=v_old_driver and tenant_id=p_tenant_id and status='busy';
  end if;
  update public.drivers set status='busy',available_since=null,updated_at=now(),last_seen_at=now() where id=p_driver_id and tenant_id=p_tenant_id;
  update public.bookings set status='assigned',assigned_driver_id=p_driver_id,assigned_at=now(),arrived_at=null,reassign_count=reassign_count+case when v_old_driver is not null and v_old_driver is distinct from p_driver_id then 1 else 0 end,updated_at=now() where id=p_booking_id and tenant_id=p_tenant_id;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.cancel_booking(p_tenant_id uuid,p_booking_id uuid,p_reason text,p_code text,p_actor_role text,p_no_show boolean default false)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_booking public.bookings; v_driver_id uuid; v_offer_driver uuid;
begin
  select * into v_booking from public.bookings where id=p_booking_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status='completed' then raise exception 'Completed booking cannot be cancelled'; end if;
  if v_booking.status in ('cancelled','no_show') then return to_jsonb(v_booking); end if;
  v_driver_id := v_booking.assigned_driver_id;
  select driver_id into v_offer_driver from public.dispatch_offers where tenant_id=p_tenant_id and booking_id=p_booking_id and response is null order by offered_at desc limit 1;
  update public.dispatch_offers set response='expired',responded_at=coalesce(responded_at,now()) where tenant_id=p_tenant_id and booking_id=p_booking_id and response is null;
  if v_offer_driver is not null then update public.drivers set status='available',available_since=now(),updated_at=now() where id=v_offer_driver and tenant_id=p_tenant_id and status='offered'; end if;
  if v_driver_id is not null then update public.drivers set status='available',available_since=now(),updated_at=now(),recent_declines=0 where id=v_driver_id and tenant_id=p_tenant_id and status='busy'; end if;
  update public.bookings set status=case when p_no_show then 'no_show'::public.booking_status else 'cancelled'::public.booking_status end,cancelled_at=now(),no_show_at=case when p_no_show then now() else no_show_at end,cancellation_reason=nullif(trim(coalesce(p_reason,'')),''),cancellation_code=nullif(trim(coalesce(p_code,'')),''),cancelled_by_role=nullif(trim(coalesce(p_actor_role,'')),''),updated_at=now() where id=p_booking_id and tenant_id=p_tenant_id;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.requeue_booking(p_tenant_id uuid,p_booking_id uuid)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_booking public.bookings; v_driver_id uuid; v_offer_driver uuid;
begin
  select * into v_booking from public.bookings where id=p_booking_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status in ('completed','in_progress') then raise exception 'Booking cannot be requeued'; end if;
  if v_booking.status='scheduled' and v_booking.scheduled_for is not null and v_booking.scheduled_for > now() then raise exception 'Scheduled booking is not active yet'; end if;
  v_driver_id := v_booking.assigned_driver_id;
  select driver_id into v_offer_driver from public.dispatch_offers where tenant_id=p_tenant_id and booking_id=p_booking_id and response is null order by offered_at desc limit 1;
  update public.dispatch_offers set response='expired',responded_at=coalesce(responded_at,now()) where tenant_id=p_tenant_id and booking_id=p_booking_id and response is null;
  if v_offer_driver is not null then update public.drivers set status='available',available_since=now(),updated_at=now() where id=v_offer_driver and tenant_id=p_tenant_id and status='offered'; end if;
  if v_driver_id is not null then update public.drivers set status='available',available_since=now(),updated_at=now() where id=v_driver_id and tenant_id=p_tenant_id and status='busy'; end if;
  update public.bookings set status='pending',assigned_driver_id=null,assigned_at=null,arrived_at=null,cancelled_at=null,cancellation_reason=null,cancellation_code=null,cancelled_by_role=null,no_show_at=null,updated_at=now() where id=p_booking_id and tenant_id=p_tenant_id;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

revoke all on function public.assign_booking_driver(uuid,uuid,uuid) from public,anon,authenticated;
revoke all on function public.cancel_booking(uuid,uuid,text,text,text,boolean) from public,anon,authenticated;
revoke all on function public.requeue_booking(uuid,uuid) from public,anon,authenticated;
grant execute on function public.assign_booking_driver(uuid,uuid,uuid) to service_role;
grant execute on function public.cancel_booking(uuid,uuid,text,text,text,boolean) to service_role;
grant execute on function public.requeue_booking(uuid,uuid) to service_role;

alter table public.customer_profiles enable row level security;
alter table public.support_tickets enable row level security;

create policy customer_profiles_dispatch_select on public.customer_profiles for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=customer_profiles.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')));
create policy support_tickets_authorized_select on public.support_tickets for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=support_tickets.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')) or exists(select 1 from public.drivers d where d.id=support_tickets.driver_id and d.auth_user_id=(select auth.uid())));

revoke all on public.customer_profiles,public.support_tickets from anon,authenticated;
grant select on public.customer_profiles,public.support_tickets to authenticated;
grant all on public.customer_profiles,public.support_tickets to service_role;
