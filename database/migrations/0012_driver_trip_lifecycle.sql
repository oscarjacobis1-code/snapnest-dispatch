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
  if v_booking.status not in ('assigned','in_progress') then raise exception 'Booking cannot be started'; end if;

  update public.bookings
  set status='in_progress', started_at=coalesce(started_at, now()), updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  update public.drivers
  set status='busy', available_since=null, last_seen_at=now(), updated_at=now()
  where id=p_driver_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

create or replace function public.complete_driver_trip(p_tenant_id uuid, p_booking_id uuid, p_driver_id uuid)
returns jsonb
language plpgsql
security invoker
set search_path=public
as $$
declare
  v_booking public.bookings;
  v_next_rank integer;
begin
  select * into v_booking
  from public.bookings
  where id=p_booking_id and tenant_id=p_tenant_id
  for update;

  if not found then raise exception 'Booking not found'; end if;
  if v_booking.assigned_driver_id is distinct from p_driver_id then raise exception 'Booking is not assigned to this driver'; end if;
  if v_booking.status not in ('assigned','in_progress') then raise exception 'Booking cannot be completed'; end if;

  select coalesce(max(queue_rank),0)+1 into v_next_rank
  from public.drivers where tenant_id=p_tenant_id;

  update public.bookings
  set status='completed', started_at=coalesce(started_at, now()), completed_at=now(), updated_at=now()
  where id=p_booking_id and tenant_id=p_tenant_id;

  update public.drivers
  set status='available', queue_rank=v_next_rank, recent_declines=0, available_since=now(), last_seen_at=now(), updated_at=now()
  where id=p_driver_id and tenant_id=p_tenant_id;

  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

revoke all on function public.start_driver_trip(uuid,uuid,uuid) from public,anon,authenticated;
revoke all on function public.complete_driver_trip(uuid,uuid,uuid) from public,anon,authenticated;
grant execute on function public.start_driver_trip(uuid,uuid,uuid) to service_role;
grant execute on function public.complete_driver_trip(uuid,uuid,uuid) to service_role;
