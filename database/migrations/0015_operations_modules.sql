-- Booking lifecycle values used by the operations modules.
-- PostgreSQL requires new enum values to be committed before later migrations use them.
alter type public.booking_status add value if not exists 'scheduled';
alter type public.booking_status add value if not exists 'no_show';
