-- Disable the one-time bootstrap path after the first production admin is provisioned manually.
alter table public.system_bootstrap
  add column if not exists disabled_at timestamptz;

update public.system_bootstrap
set disabled_at = coalesce(disabled_at, now()),
    code_hash = encode(digest(gen_random_uuid()::text, 'sha256'), 'hex')
where key = 'initial_admin';
