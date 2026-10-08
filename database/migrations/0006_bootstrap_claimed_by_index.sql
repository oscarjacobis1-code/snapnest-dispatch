create index if not exists system_bootstrap_claimed_by_idx
on public.system_bootstrap(claimed_by)
where claimed_by is not null;
