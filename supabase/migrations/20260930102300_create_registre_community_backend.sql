create table if not exists public.registre_counters (
  year integer not null,
  register_type text not null,
  last_number bigint not null default 0 check (last_number >= 0),
  updated_at timestamptz not null default now(),
  primary key (year, register_type),
  constraint registre_counters_type_chk check (register_type in ('/2','/3','/4','/3.S','/3.PERM'))
);

create table if not exists public.registre_entries (
  id uuid primary key default gen_random_uuid(),
  year integer not null,
  register_type text not null,
  official_number bigint not null check (official_number > 0),
  display_number text not null,
  status text not null default 'NUMBERED',
  created_at timestamptz not null default now(),
  created_by uuid null default auth.uid(),

  piece_number text not null default '',
  piece_date date null,
  origin text not null default '',
  label text not null default '',
  observation text not null default '',

  grade text not null default '',
  full_name text not null default '',
  matricule text not null default '',
  number_r3 text not null default '',
  departure_date date null,
  arrival_date date null,
  annual_right integer not null default 0 check (annual_right >= 0),
  consumed_right integer not null default 0 check (consumed_right >= 0),

  movement_kind text not null default '',
  duration_days integer not null default 0 check (duration_days >= 0),
  beneficiary text not null default '',

  constraint registre_entries_type_chk check (register_type in ('/2','/3','/4','/3.S','/3.PERM')),
  constraint registre_entries_status_chk check (status = 'NUMBERED'),
  constraint registre_entries_unique_number unique (year, register_type, official_number)
);

create index if not exists registre_entries_year_type_number_idx
  on public.registre_entries (year, register_type, official_number);

alter table public.registre_entries enable row level security;
alter table public.registre_counters enable row level security;

drop policy if exists "registre lecture communautaire" on public.registre_entries;
create policy "registre lecture communautaire"
on public.registre_entries
for select
to anon, authenticated
using (true);

revoke all on table public.registre_counters from anon, authenticated;
grant select on table public.registre_entries to anon, authenticated;

create or replace function public.registre_create_entry(p_payload jsonb)
returns public.registre_entries
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_year integer;
  v_type text;
  v_number bigint;
  v_row public.registre_entries;
  v_current_year integer;
begin
  v_current_year := extract(year from timezone('Indian/Antananarivo', now()))::integer;
  v_year := coalesce(nullif(p_payload->>'year', '')::integer, v_current_year);
  v_type := p_payload->>'registerType';

  if v_type is null or v_type not in ('/2','/3','/4','/3.S','/3.PERM') then
    raise exception 'Type de registre invalide';
  end if;

  if v_year <> v_current_year then
    raise exception 'Cette année est clôturée et reste en lecture seule';
  end if;

  insert into public.registre_counters(year, register_type, last_number, updated_at)
  values (v_year, v_type, 1, now())
  on conflict (year, register_type)
  do update set
    last_number = public.registre_counters.last_number + 1,
    updated_at = now()
  returning last_number into v_number;

  insert into public.registre_entries (
    year, register_type, official_number, display_number, status, created_by,
    piece_number, piece_date, origin, label, observation,
    grade, full_name, matricule, number_r3,
    departure_date, arrival_date, annual_right, consumed_right,
    movement_kind, duration_days, beneficiary
  )
  values (
    v_year,
    v_type,
    v_number,
    v_number::text || v_type,
    'NUMBERED',
    auth.uid(),
    coalesce(p_payload->>'pieceNumber',''),
    nullif(p_payload->>'pieceDate','')::date,
    coalesce(p_payload->>'origin',''),
    coalesce(p_payload->>'label',''),
    coalesce(p_payload->>'observation',''),
    coalesce(p_payload->>'grade',''),
    coalesce(p_payload->>'fullName',''),
    coalesce(p_payload->>'matricule',''),
    coalesce(p_payload->>'numberR3',''),
    nullif(p_payload->>'departureDate','')::date,
    nullif(p_payload->>'arrivalDate','')::date,
    coalesce(nullif(p_payload->>'annualRight','')::integer, 0),
    coalesce(nullif(p_payload->>'consumedRight','')::integer, 0),
    coalesce(p_payload->>'movementKind',''),
    coalesce(nullif(p_payload->>'durationDays','')::integer, 0),
    coalesce(p_payload->>'beneficiary','')
  )
  returning * into v_row;

  return v_row;
end;
$$;

revoke all on function public.registre_create_entry(jsonb) from public;
grant execute on function public.registre_create_entry(jsonb) to anon, authenticated;

do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (
       select 1
       from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'public'
         and tablename = 'registre_entries'
     ) then
    alter publication supabase_realtime add table public.registre_entries;
  end if;
end $$;
