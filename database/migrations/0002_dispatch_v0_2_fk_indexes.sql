-- Performance indexes recommended by Supabase database advisors.
create index if not exists bookings_assigned_driver_idx on public.bookings(assigned_driver_id) where assigned_driver_id is not null;
create index if not exists communications_booking_idx on public.communications(booking_id) where booking_id is not null;
create index if not exists offers_driver_idx on public.dispatch_offers(driver_id);
create index if not exists driver_locations_tenant_idx on public.driver_locations(tenant_id);
