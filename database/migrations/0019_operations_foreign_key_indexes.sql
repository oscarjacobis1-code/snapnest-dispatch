-- Cover operational foreign keys used by cleanup, joins, and reservation/support lookups.

create index if not exists bookings_reserved_driver_fk_idx
  on public.bookings(reserved_driver_id)
  where reserved_driver_id is not null;

create index if not exists support_tickets_booking_fk_idx
  on public.support_tickets(booking_id)
  where booking_id is not null;

create index if not exists support_tickets_created_by_user_fk_idx
  on public.support_tickets(created_by_user_id)
  where created_by_user_id is not null;
