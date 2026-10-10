alter table public.dispatch_offers
  add column if not exists eta_seconds integer check (eta_seconds is null or eta_seconds >= 0),
  add column if not exists road_distance_km numeric check (road_distance_km is null or road_distance_km >= 0),
  add column if not exists routing_provider text,
  add column if not exists routing_profile text;

create or replace function public.create_dispatch_offer_routed(
  p_tenant_id uuid,
  p_booking_id uuid,
  p_driver_id uuid,
  p_score numeric,
  p_distance_km numeric,
  p_eta_seconds integer,
  p_road_distance_km numeric,
  p_routing_provider text,
  p_routing_profile text,
  p_expires_at timestamptz
)
returns jsonb language plpgsql security invoker set search_path=public as $$
declare v_booking public.bookings; v_count integer;
begin
  select * into v_booking from public.bookings where id=p_booking_id and tenant_id=p_tenant_id for update;
  if not found then raise exception 'Booking not found'; end if;
  if v_booking.status not in ('pending','offering') then raise exception 'Booking is not dispatchable'; end if;
  update public.drivers set status='offered',updated_at=now(),last_seen_at=coalesce(last_seen_at,now()) where id=p_driver_id and tenant_id=p_tenant_id and status='available';
  get diagnostics v_count=row_count;
  if v_count<>1 then raise exception 'Driver is no longer available'; end if;
  insert into public.dispatch_offers(
    tenant_id,booking_id,driver_id,score,distance_km,eta_seconds,road_distance_km,routing_provider,routing_profile,expires_at
  ) values(
    p_tenant_id,p_booking_id,p_driver_id,p_score,p_distance_km,p_eta_seconds,p_road_distance_km,p_routing_provider,p_routing_profile,p_expires_at
  );
  update public.bookings set status='offering',updated_at=now() where id=p_booking_id;
  select * into v_booking from public.bookings where id=p_booking_id;
  return to_jsonb(v_booking);
end $$;

revoke all on function public.create_dispatch_offer_routed(uuid,uuid,uuid,numeric,numeric,integer,numeric,text,text,timestamptz) from public,anon,authenticated;
grant execute on function public.create_dispatch_offer_routed(uuid,uuid,uuid,numeric,numeric,integer,numeric,text,text,timestamptz) to service_role;
