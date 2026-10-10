-- Private screenshot storage for driver support tickets.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'dispatch-support',
  'dispatch-support',
  false,
  3145728,
  array['image/jpeg','image/png','image/webp']
)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;
