alter table public.operator_shifts
  add column if not exists operator_email text;
