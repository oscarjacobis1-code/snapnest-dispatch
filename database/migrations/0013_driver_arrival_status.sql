alter type public.booking_status add value if not exists 'arrived' after 'assigned';

alter table public.bookings
  add column if not exists arrived_at timestamptz;
