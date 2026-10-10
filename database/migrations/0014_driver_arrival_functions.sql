create or replace function public.arrive_driver_trip(p_tenant_id uuid, p_booking_id uuid, p_driver_id uuid)
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
  if v_booking.assigned_driver_id is distinct from p_driver_id then raise exception 'Booking is not assigned to this driver'; end if;
  if v_booking.status not in ('assigned','arrived') then raise exception 'Booking cannot be marked arrived'; end if;

  update public.bookings
  set status='arrived', arrived_at=coalesce(arrived_at, now()), updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  update public.drivers
  set status='busy', available_since=null, last_seen_at=now(), updated_at=now()
  where id=p_driver_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.start_driver_trip(p_tenant_id uuid, p_booking_id uuid, p_driver_id uuid)
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
  if v_booking.assigned_driver_id is distinct from p_driver_id then raise exception 'Booking is not assigned to this driver'; end if;
  if v_booking.status not in ('assigned','arrived','in_progress') then raise exception 'Booking cannot be started'; end if;

  update public.bookings
  set status='in_progress', started_at=coalesce(started_at, now()), updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  update public.drivers
  set status='busy', available_since=null, last_seen_at=now(), updated_at=now()
  where id=p_driver_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

revoke all on function public.arrive_driver_trip(uuid,uuid,uuid) from public,anon,authenticated;
grant execute on function public.arrive_driver_trip(uuid,uuid,uuid) to service_role;
