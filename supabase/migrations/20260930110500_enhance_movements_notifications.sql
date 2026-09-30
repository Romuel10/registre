-- Evolutions du registre communautaire :
-- calendrier /3.PERM, durée indéterminée, droits annuels,
-- identification locale des appareils et notifications communautaires.

alter table public.registre_entries
  add column if not exists annual_right_year integer,
  add column if not exists consumed_right_detail text not null default '',
  add column if not exists duration_indefinite boolean not null default false,
  add column if not exists movement_closed_at timestamptz,
  add column if not exists creator_device_id text not null default '';

update public.registre_entries
set annual_right_year = case
  when annual_right between 1900 and 2200 then annual_right
  else year
end
where annual_right_year is null;

alter table public.registre_entries
  alter column annual_right_year
  set default extract(year from timezone('Indian/Antananarivo', now()))::integer;

create index if not exists registre_entries_created_at_idx
  on public.registre_entries (created_at);

create index if not exists registre_entries_creator_device_idx
  on public.registre_entries (creator_device_id, created_at);

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
  v_indefinite boolean;
  v_annual_right_year integer;
  v_entry_id uuid;
begin
  v_current_year := extract(year from timezone('Indian/Antananarivo', now()))::integer;
  v_year := coalesce(nullif(p_payload->>'year', '')::integer, v_current_year);
  v_type := p_payload->>'registerType';
  v_indefinite := coalesce(nullif(p_payload->>'durationIndefinite','')::boolean, false);
  v_annual_right_year := coalesce(
    nullif(p_payload->>'annualRightYear','')::integer,
    nullif(p_payload->>'annualRight','')::integer,
    v_current_year
  );
  v_entry_id := coalesce(nullif(p_payload->>'entryId','')::uuid, gen_random_uuid());

  if v_type is null or v_type not in ('/2','/3','/4','/3.S','/3.PERM') then
    raise exception 'Type de registre invalide';
  end if;

  if v_year <> v_current_year then
    raise exception 'Cette année est clôturée et reste en lecture seule';
  end if;

  if v_indefinite = false
     and coalesce(nullif(p_payload->>'durationDays','')::integer, 0) > 0
     and nullif(p_payload->>'arrivalDate','') is null then
    raise exception 'Date d''arrivée requise pour une durée déterminée';
  end if;

  insert into public.registre_counters(year, register_type, last_number, updated_at)
  values (v_year, v_type, 1, now())
  on conflict (year, register_type)
  do update set
    last_number = public.registre_counters.last_number + 1,
    updated_at = now()
  returning last_number into v_number;

  insert into public.registre_entries (
    id, year, register_type, official_number, display_number, status, created_by,
    piece_number, piece_date, origin, label, observation,
    grade, full_name, matricule, number_r3,
    departure_date, arrival_date, annual_right, consumed_right,
    annual_right_year, consumed_right_detail,
    movement_kind, duration_days, duration_indefinite, movement_closed_at,
    beneficiary, creator_device_id
  )
  values (
    v_entry_id,
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
    case when v_indefinite then null else nullif(p_payload->>'arrivalDate','')::date end,
    0,
    0,
    v_annual_right_year,
    coalesce(p_payload->>'consumedRightDetail', p_payload->>'consumedRight', ''),
    coalesce(p_payload->>'movementKind',''),
    case when v_indefinite then 0 else coalesce(nullif(p_payload->>'durationDays','')::integer, 0) end,
    v_indefinite,
    case when coalesce(nullif(p_payload->>'movementClosed','')::boolean, false) then now() else null end,
    coalesce(p_payload->>'beneficiary',''),
    coalesce(p_payload->>'creatorDeviceId','')
  )
  returning * into v_row;

  return v_row;
end;
$$;

create or replace function public.registre_close_movement(
  p_entry_id uuid,
  p_device_id text
)
returns public.registre_entries
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row public.registre_entries;
begin
  update public.registre_entries
  set movement_closed_at = now()
  where id = p_entry_id
    and duration_indefinite = true
    and movement_closed_at is null
    and creator_device_id = p_device_id
  returning * into v_row;

  if v_row.id is null then
    raise exception 'Déplacement introuvable, déjà clôturé ou non créé sur cet appareil';
  end if;

  return v_row;
end;
$$;

revoke all on function public.registre_close_movement(uuid, text) from public;
grant execute on function public.registre_close_movement(uuid, text) to anon, authenticated;
