-- SnapNest Dispatch production schema (PostgreSQL / Supabase-ready)
create extension if not exists pgcrypto;

create table if not exists tenants (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  slug text not null unique,
  brand_config jsonb not null default '{}'::jsonb,
  timezone text not null default 'America/Guyana',
  created_at timestamptz not null default now()
);

create type driver_status as enum ('available','offered','busy','unavailable','offline');
create type booking_status as enum ('pending','offering','assigned','in_progress','completed','cancelled','unfulfilled');

create table if not exists drivers (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  display_name text not null,
  phone_e164 text,
  vehicle_plate text,
  status driver_status not null default 'offline',
  queue_rank integer not null default 0,
  recent_declines integer not null default 0,
  available_since timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists driver_locations (
  driver_id uuid primary key references drivers(id) on delete cascade,
  tenant_id uuid not null references tenants(id) on delete cascade,
  latitude double precision not null,
  longitude double precision not null,
  accuracy_m double precision,
  captured_at timestamptz not null default now()
);

create table if not exists bookings (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  source text not null check (source in ('whatsapp','phone','web','dispatcher')),
  customer_name text,
  customer_phone_e164 text,
  passengers integer not null default 1 check (passengers between 1 and 8),
  pickup_label text not null,
  pickup_lat double precision not null,
  pickup_lng double precision not null,
  destination_label text not null,
  destination_lat double precision,
  destination_lng double precision,
  notes text,
  status booking_status not null default 'pending',
  assigned_driver_id uuid references drivers(id),
  created_at timestamptz not null default now(),
  assigned_at timestamptz,
  completed_at timestamptz
);

create table if not exists dispatch_offers (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  booking_id uuid not null references bookings(id) on delete cascade,
  driver_id uuid not null references drivers(id) on delete cascade,
  score numeric,
  distance_km numeric,
  offered_at timestamptz not null default now(),
  expires_at timestamptz not null,
  responded_at timestamptz,
  response text check (response in ('accepted','declined','expired')),
  unique (booking_id, driver_id)
);

create table if not exists communications (
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references tenants(id) on delete cascade,
  booking_id uuid references bookings(id) on delete set null,
  channel text not null check (channel in ('whatsapp','phone','ptt','sms')),
  direction text not null check (direction in ('inbound','outbound')),
  external_id text,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists bookings_tenant_status_idx on bookings(tenant_id, status, created_at desc);
create index if not exists drivers_tenant_status_idx on drivers(tenant_id, status);
create index if not exists offers_booking_idx on dispatch_offers(booking_id, offered_at desc);
