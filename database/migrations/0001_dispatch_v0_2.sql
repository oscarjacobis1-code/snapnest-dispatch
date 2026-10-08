-- SnapNest Dispatch v0.2 - Supabase production schema
create extension if not exists pgcrypto;

do $$ begin create type public.driver_status as enum ('available','offered','busy','unavailable','offline'); exception when duplicate_object then null; end $$;
do $$ begin create type public.booking_status as enum ('pending','offering','assigned','in_progress','completed','cancelled','unfulfilled'); exception when duplicate_object then null; end $$;
do $$ begin create type public.tenant_role as enum ('admin','dispatcher','driver'); exception when duplicate_object then null; end $$;

create table if not exists public.tenants (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  slug text not null unique,
  brand_config jsonb not null default '{}'::jsonb,
  timezone text not null default 'America/Guyana',
  night_mode boolean not null default false,
  offer_timeout_seconds integer not null default 20 check (offer_timeout_seconds between 5 and 120),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.memberships (
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role public.tenant_role not null,
  created_at timestamptz not null default now(),
  primary key (tenant_id, user_id)
);

create table if not exists public.drivers (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  auth_user_id uuid references auth.users(id) on delete set null,
  display_name text not null,
  phone_e164 text,
  vehicle_plate text,
  status public.driver_status not null default 'offline',
  queue_rank integer not null default 0,
  recent_declines integer not null default 0,
  available_since timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (tenant_id, auth_user_id)
);

create table if not exists public.driver_locations (
  driver_id uuid primary key references public.drivers(id) on delete cascade,
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy_m double precision,
  captured_at timestamptz not null default now()
);

create table if not exists public.bookings (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  source text not null check (source in ('whatsapp','phone','web','dispatcher')),
  customer_name text,
  customer_phone_e164 text,
  passengers integer not null default 1 check (passengers between 1 and 8),
  pickup_label text not null,
  pickup_lat double precision not null check (pickup_lat between -90 and 90),
  pickup_lng double precision not null check (pickup_lng between -180 and 180),
  destination_label text not null,
  destination_lat double precision check (destination_lat between -90 and 90),
  destination_lng double precision check (destination_lng between -180 and 180),
  notes text,
  status public.booking_status not null default 'pending',
  assigned_driver_id uuid references public.drivers(id) on delete set null,
  created_at timestamptz not null default now(),
  assigned_at timestamptz,
  started_at timestamptz,
  completed_at timestamptz,
  updated_at timestamptz not null default now()
);

create table if not exists public.dispatch_offers (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  booking_id uuid not null references public.bookings(id) on delete cascade,
  driver_id uuid not null references public.drivers(id) on delete cascade,
  score numeric,
  distance_km numeric,
  offered_at timestamptz not null default now(),
  expires_at timestamptz not null,
  responded_at timestamptz,
  response text check (response in ('accepted','declined','expired')),
  unique (booking_id, driver_id)
);

create table if not exists public.communications (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  booking_id uuid references public.bookings(id) on delete set null,
  channel text not null check (channel in ('whatsapp','phone','ptt','sms')),
  direction text not null check (direction in ('inbound','outbound')),
  external_id text,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table if not exists public.dispatch_events (
  id bigint generated always as identity primary key,
  tenant_id uuid not null references public.tenants(id) on delete cascade,
  event_type text not null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists memberships_user_idx on public.memberships(user_id, tenant_id);
create index if not exists drivers_tenant_status_idx on public.drivers(tenant_id, status);
create index if not exists drivers_auth_user_idx on public.drivers(auth_user_id) where auth_user_id is not null;
create index if not exists bookings_tenant_status_idx on public.bookings(tenant_id, status, created_at desc);
create index if not exists offers_booking_idx on public.dispatch_offers(booking_id, offered_at desc);
create index if not exists offers_expiry_idx on public.dispatch_offers(tenant_id, expires_at) where response is null;
create index if not exists communications_tenant_idx on public.communications(tenant_id, created_at desc);
create index if not exists events_tenant_idx on public.dispatch_events(tenant_id, created_at desc);

create or replace function public.create_dispatch_offer(p_tenant_id uuid,p_booking_id uuid,p_driver_id uuid,p_score numeric,p_distance_km numeric,p_expires_at timestamptz)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_booking public.bookings; v_count integer;
begin
  select * into v_booking from public.bookings where id=p_booking_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status not in ('pending','offering') then raise exception 'Booking is not dispatchable'; end if;
  update public.drivers set status='offered',updated_at=now(),last_seen_at=coalesce(last_seen_at,now()) where id=p_driver_id and tenant_id=p_tenant_id and status='available';
  get diagnostics v_count=row_count;
  if v_count<>1 then raise exception 'Driver is no longer available'; end if;
  insert into public.dispatch_offers(tenant_id,booking_id,driver_id,score,distance_km,expires_at) values(p_tenant_id,p_booking_id,p_driver_id,p_score,p_distance_km,p_expires_at);
  update public.bookings set status='offering',updated_at=now() where id=p_booking_id;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.resolve_dispatch_offer(p_tenant_id uuid,p_booking_id uuid,p_driver_id uuid,p_accept boolean)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_offer public.dispatch_offers; v_booking public.bookings;
begin
  select * into v_offer from public.dispatch_offers where tenant_id=p_tenant_id and booking_id=p_booking_id and driver_id=p_driver_id and response is null order by offered_at desc limit 1 for update;
  if not found then raise exception 'This offer is no longer active'; end if;
  if v_offer.expires_at<=now() then raise exception 'This offer has expired'; end if;
  if p_accept then
    update public.dispatch_offers set response='accepted',responded_at=now() where id=v_offer.id;
    update public.drivers set status='busy',available_since=null,updated_at=now(),last_seen_at=now() where id=p_driver_id and tenant_id=p_tenant_id;
    update public.bookings set status='assigned',assigned_driver_id=p_driver_id,assigned_at=now(),updated_at=now() where id=p_booking_id and tenant_id=p_tenant_id;
  else
    update public.dispatch_offers set response='declined',responded_at=now() where id=v_offer.id;
    update public.drivers set status='available',recent_declines=recent_declines+1,available_since=now(),updated_at=now(),last_seen_at=now() where id=p_driver_id and tenant_id=p_tenant_id;
    update public.bookings set status='pending',updated_at=now() where id=p_booking_id and tenant_id=p_tenant_id and status='offering';
  end if;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.expire_dispatch_offer(p_tenant_id uuid,p_offer_id uuid)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_offer public.dispatch_offers;
begin
  select * into v_offer from public.dispatch_offers where id=p_offer_id and tenant_id=p_tenant_id for update;
  if not found then return jsonb_build_object('expired',false); end if;
  if v_offer.response is not null or v_offer.expires_at>now() then return jsonb_build_object('expired',false); end if;
  update public.dispatch_offers set response='expired',responded_at=now() where id=v_offer.id;
  update public.drivers set status='available',available_since=now(),updated_at=now() where id=v_offer.driver_id and tenant_id=p_tenant_id and status='offered';
  update public.bookings set status='pending',updated_at=now() where id=v_offer.booking_id and tenant_id=p_tenant_id and status='offering';
  return jsonb_build_object('expired',true,'booking_id',v_offer.booking_id,'driver_id',v_offer.driver_id);
end $$;

revoke all on function public.create_dispatch_offer(uuid,uuid,uuid,numeric,numeric,timestamptz) from public,anon,authenticated;
revoke all on function public.resolve_dispatch_offer(uuid,uuid,uuid,boolean) from public,anon,authenticated;
revoke all on function public.expire_dispatch_offer(uuid,uuid) from public,anon,authenticated;
grant execute on function public.create_dispatch_offer(uuid,uuid,uuid,numeric,numeric,timestamptz) to service_role;
grant execute on function public.resolve_dispatch_offer(uuid,uuid,uuid,boolean) to service_role;
grant execute on function public.expire_dispatch_offer(uuid,uuid) to service_role;

alter table public.tenants enable row level security;
alter table public.memberships enable row level security;
alter table public.drivers enable row level security;
alter table public.driver_locations enable row level security;
alter table public.bookings enable row level security;
alter table public.dispatch_offers enable row level security;
alter table public.communications enable row level security;
alter table public.dispatch_events enable row level security;

create policy memberships_self_select on public.memberships for select to authenticated using(user_id=(select auth.uid()));
create policy tenants_member_select on public.tenants for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=tenants.id and m.user_id=(select auth.uid())));
create policy drivers_member_select on public.drivers for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=drivers.tenant_id and m.user_id=(select auth.uid())));
create policy drivers_authorized_update on public.drivers for update to authenticated using(auth_user_id=(select auth.uid()) or exists(select 1 from public.memberships m where m.tenant_id=drivers.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher'))) with check(auth_user_id=(select auth.uid()) or exists(select 1 from public.memberships m where m.tenant_id=drivers.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')));
create policy locations_authorized_select on public.driver_locations for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=driver_locations.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')) or exists(select 1 from public.drivers d where d.id=driver_locations.driver_id and d.auth_user_id=(select auth.uid())));
create policy locations_own_update on public.driver_locations for update to authenticated using(exists(select 1 from public.drivers d where d.id=driver_locations.driver_id and d.auth_user_id=(select auth.uid()))) with check(exists(select 1 from public.drivers d where d.id=driver_locations.driver_id and d.auth_user_id=(select auth.uid())));
create policy bookings_member_select on public.bookings for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=bookings.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')) or exists(select 1 from public.drivers d where d.id=bookings.assigned_driver_id and d.auth_user_id=(select auth.uid())) or exists(select 1 from public.dispatch_offers o join public.drivers d on d.id=o.driver_id where o.booking_id=bookings.id and o.response is null and d.auth_user_id=(select auth.uid())));
create policy offers_member_select on public.dispatch_offers for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=dispatch_offers.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')) or exists(select 1 from public.drivers d where d.id=dispatch_offers.driver_id and d.auth_user_id=(select auth.uid())));
create policy communications_dispatch_select on public.communications for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=communications.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')));
create policy events_dispatch_select on public.dispatch_events for select to authenticated using(exists(select 1 from public.memberships m where m.tenant_id=dispatch_events.tenant_id and m.user_id=(select auth.uid()) and m.role in ('admin','dispatcher')));

revoke all on public.tenants,public.memberships,public.drivers,public.driver_locations,public.bookings,public.dispatch_offers,public.communications,public.dispatch_events from anon,authenticated;
grant select on public.tenants,public.memberships,public.drivers,public.driver_locations,public.bookings,public.dispatch_offers,public.communications,public.dispatch_events to authenticated;
grant update(status,available_since,last_seen_at,updated_at) on public.drivers to authenticated;
grant update(latitude,longitude,accuracy_m,captured_at) on public.driver_locations to authenticated;
grant all on public.tenants,public.memberships,public.drivers,public.driver_locations,public.bookings,public.dispatch_offers,public.communications,public.dispatch_events to service_role;
grant usage,select on all sequences in schema public to service_role;
