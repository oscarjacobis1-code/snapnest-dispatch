-- Development seed for a fresh SnapNest Dispatch Supabase project.
with tenant as (
  insert into public.tenants(name,slug,brand_config)
  values('SnapNest Dispatch Demo Base','demo-base','{"accent":"#111827"}'::jsonb)
  on conflict(slug) do update set name=excluded.name
  returning id
)
insert into public.drivers(tenant_id,display_name,vehicle_plate,status,queue_rank,available_since)
select id,'Car 08','HC 3088','available',1,now()-interval '42 minutes' from tenant
union all select id,'Car 03','HD 1703','available',2,now()-interval '25 minutes' from tenant
union all select id,'Car 12','HC 9212','busy',3,null from tenant
union all select id,'Car 15','HB 4515','available',4,now()-interval '8 minutes' from tenant;

insert into public.driver_locations(driver_id,tenant_id,latitude,longitude,accuracy_m)
select d.id,d.tenant_id,
  case d.display_name when 'Car 08' then 6.8208 when 'Car 03' then 6.8149 when 'Car 12' then 6.8072 else 6.8263 end,
  case d.display_name when 'Car 08' then -58.1551 when 'Car 03' then -58.1515 when 'Car 12' then -58.1666 else -58.1624 end,
  15
from public.drivers d join public.tenants t on t.id=d.tenant_id
where t.slug='demo-base'
on conflict(driver_id) do update set latitude=excluded.latitude,longitude=excluded.longitude,captured_at=now();
