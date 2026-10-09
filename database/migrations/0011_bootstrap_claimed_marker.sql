update public.system_bootstrap
set disabled_at = coalesce(disabled_at, now())
where key = 'initial_admin';
