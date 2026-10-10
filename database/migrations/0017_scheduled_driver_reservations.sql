-- Reserve future scheduled bookings to a specific driver without marking that driver busy now.

alter table public.bookings
  add column if not exists reserved_driver_id uuid references public.drivers(id) on delete set null;

create index if not exists bookings_reserved_driver_schedule_idx
  on public.bookings(tenant_id, reserved_driver_id, scheduled_for)
  where status='scheduled' and reserved_driver_id is not null;

create or replace function public.reserve_scheduled_booking(
  p_tenant_id uuid,
  p_booking_id uuid,
  p_driver_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path=public
as $$
declare
  v_booking public.bookings;
  v_driver public.drivers;
begin
  select * into v_booking
  from public.bookings
  where id=p_booking_id and tenant_id=p_tenant_id
  for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status <> 'scheduled' then raise exception 'Only scheduled bookings can be reserved'; end if;
  if v_booking.scheduled_for is null or v_booking.scheduled_for <= now() then raise exception 'Scheduled pickup time must be in the future'; end if;

  select * into v_driver
  from public.drivers
  where id=p_driver_id and tenant_id=p_tenant_id;
  if not found then raise exception 'Driver not found'; end if;

  update public.bookings
  set reserved_driver_id=p_driver_id, updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.clear_scheduled_reservation(
  p_tenant_id uuid,
  p_booking_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path=public
as $$
declare
  v_booking public.bookings;
begin
  select * into v_booking
  from public.bookings
  where id=p_booking_id and tenant_id=p_tenant_id
  for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status <> 'scheduled' then raise exception 'Only scheduled bookings can change reservations'; end if;

  update public.bookings
  set reserved_driver_id=null, updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

revoke all on function public.reserve_scheduled_booking(uuid,uuid,uuid) from public,anon,authenticated;
revoke all on function public.clear_scheduled_reservation(uuid,uuid) from public,anon,authenticated;
grant execute on function public.reserve_scheduled_booking(uuid,uuid,uuid) to service_role;
grant execute on function public.clear_scheduled_reservation(uuid,uuid) to service_role;
