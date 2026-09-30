-- Cycle de vie des entrées, messages de disponibilité et contrôle renforcé des doublons.

alter table public.registre_entries
  add column if not exists message_kind text not null default '',
  add column if not exists related_movement_id uuid,
  add column if not exists related_permission_id uuid,
  add column if not exists cancelled_at timestamptz,
  add column if not exists cancelled_reason text not null default '',
  add column if not exists deleted_at timestamptz;

alter table public.registre_entries
  drop constraint if exists registre_entries_status_chk;

alter table public.registre_entries
  add constraint registre_entries_status_chk
  check (status in ('NUMBERED','CANCELLED','DELETED'));

alter table public.registre_entries
  drop constraint if exists registre_entries_message_kind_chk;

alter table public.registre_entries
  add constraint registre_entries_message_kind_chk
  check (message_kind in ('','ordinary','movement','availability','permission'));

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'registre_entries_related_movement_fkey'
  ) then
    alter table public.registre_entries
      add constraint registre_entries_related_movement_fkey
      foreign key (related_movement_id)
      references public.registre_entries(id);
  end if;

  if not exists (
    select 1 from pg_constraint
    where conname = 'registre_entries_related_permission_fkey'
  ) then
    alter table public.registre_entries
      add constraint registre_entries_related_permission_fkey
      foreign key (related_permission_id)
      references public.registre_entries(id);
  end if;
end $$;

create unique index if not exists registre_one_availability_per_movement_idx
on public.registre_entries(related_movement_id)
where message_kind = 'availability'
  and related_movement_id is not null
  and status = 'NUMBERED'
  and deleted_at is null;

create index if not exists registre_entries_message_kind_idx
on public.registre_entries(year, register_type, message_kind, created_at);

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
  v_max bigint;
  v_counter bigint;
  v_row public.registre_entries;
  v_current_year integer;
  v_indefinite boolean;
  v_annual_right_year integer;
  v_entry_id uuid;
  v_message_kind text;
  v_related_movement uuid;
  v_related_permission uuid;
  v_duplicate_count integer;
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
  v_message_kind := coalesce(nullif(p_payload->>'messageKind',''), '');
  v_related_movement := nullif(p_payload->>'relatedMovementId','')::uuid;
  v_related_permission := nullif(p_payload->>'relatedPermissionId','')::uuid;

  if v_type is null or v_type not in ('/2','/3','/4','/3.S','/3.PERM') then
    raise exception 'Type de registre invalide';
  end if;

  if v_year <> v_current_year then
    raise exception 'Cette année est clôturée et reste en lecture seule';
  end if;

  select count(*) into v_duplicate_count
  from (
    select official_number
    from public.registre_entries
    where year = v_year and register_type = v_type
    group by official_number
    having count(*) > 1
  ) d;

  if v_duplicate_count > 0 then
    raise exception 'Doublon de numéro détecté dans %. Enregistrement bloqué.', v_type;
  end if;

  if v_message_kind = 'availability' then
    if v_type <> '/2' then
      raise exception 'Le message de disponibilité doit être enregistré dans /2';
    end if;
    if v_related_movement is null then
      raise exception 'Sélectionnez le message de déplacement concerné';
    end if;
    if not exists (
      select 1 from public.registre_entries m
      where m.id = v_related_movement
        and m.register_type = '/2'
        and m.message_kind = 'movement'
        and m.status = 'NUMBERED'
        and m.deleted_at is null
    ) then
      raise exception 'Message de déplacement introuvable ou annulé';
    end if;
    if exists (
      select 1 from public.registre_entries a
      where a.related_movement_id = v_related_movement
        and a.message_kind = 'availability'
        and a.status = 'NUMBERED'
        and a.deleted_at is null
    ) then
      raise exception 'Un message de disponibilité existe déjà pour ce déplacement';
    end if;
  end if;

  insert into public.registre_counters(year, register_type, last_number, updated_at)
  values (v_year, v_type, 0, now())
  on conflict (year, register_type) do nothing;

  select last_number into v_counter
  from public.registre_counters
  where year = v_year and register_type = v_type
  for update;

  select coalesce(max(official_number), 0) into v_max
  from public.registre_entries
  where year = v_year and register_type = v_type;

  v_number := greatest(coalesce(v_counter, 0) + 1, v_max + 1);

  if exists (
    select 1 from public.registre_entries
    where year = v_year
      and register_type = v_type
      and official_number = v_number
  ) then
    raise exception 'Numéro % déjà utilisé dans %. Enregistrement bloqué.', v_number, v_type;
  end if;

  update public.registre_counters
  set last_number = v_number,
      updated_at = now()
  where year = v_year and register_type = v_type;

  insert into public.registre_entries (
    id, year, register_type, official_number, display_number, status, created_by,
    piece_number, piece_date, origin, label, observation,
    grade, full_name, matricule, number_r3,
    departure_date, arrival_date, annual_right, consumed_right,
    annual_right_year, consumed_right_detail,
    movement_kind, duration_days, duration_indefinite, movement_closed_at,
    beneficiary, creator_device_id,
    message_kind, related_movement_id, related_permission_id
  )
  values (
    v_entry_id, v_year, v_type, v_number, v_number::text || v_type,
    'NUMBERED', auth.uid(),
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
    0, 0, v_annual_right_year,
    coalesce(p_payload->>'consumedRightDetail', p_payload->>'consumedRight', ''),
    coalesce(p_payload->>'movementKind',''),
    case when v_indefinite then 0 else coalesce(nullif(p_payload->>'durationDays','')::integer, 0) end,
    v_indefinite,
    case when coalesce(nullif(p_payload->>'movementClosed','')::boolean, false) then now() else null end,
    coalesce(p_payload->>'beneficiary',''),
    coalesce(p_payload->>'creatorDeviceId',''),
    case
      when v_type = '/3.PERM' then 'permission'
      when v_message_kind <> '' then v_message_kind
      else 'ordinary'
    end,
    v_related_movement,
    v_related_permission
  )
  returning * into v_row;

  if v_message_kind = 'availability' and v_related_movement is not null then
    update public.registre_entries
    set movement_closed_at = coalesce(movement_closed_at, now())
    where id = v_related_movement;
  end if;

  return v_row;
exception
  when unique_violation then
    raise exception 'Doublon bloqué : ce numéro ou ce message de disponibilité existe déjà';
end;
$$;

create or replace function public.registre_cancel_entry(
  p_entry_id uuid,
  p_device_id text,
  p_reason text default ''
)
returns public.registre_entries
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_row public.registre_entries;
  v_related uuid;
begin
  select related_movement_id into v_related
  from public.registre_entries
  where id = p_entry_id;

  update public.registre_entries
  set status = 'CANCELLED',
      cancelled_at = now(),
      cancelled_reason = coalesce(p_reason, ''),
      movement_closed_at = case when message_kind = 'movement' then now() else movement_closed_at end
  where id = p_entry_id
    and creator_device_id = p_device_id
    and status = 'NUMBERED'
    and deleted_at is null
  returning * into v_row;

  if v_row.id is null then
    raise exception 'Entrée introuvable, déjà annulée ou créée sur un autre appareil';
  end if;

  return v_row;
end;
$$;

create or replace function public.registre_delete_entry(
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
  set status = 'DELETED',
      deleted_at = now(),
      movement_closed_at = case when message_kind = 'movement' then now() else movement_closed_at end
  where id = p_entry_id
    and creator_device_id = p_device_id
    and status in ('NUMBERED','CANCELLED')
    and deleted_at is null
  returning * into v_row;

  if v_row.id is null then
    raise exception 'Entrée introuvable, déjà supprimée ou créée sur un autre appareil';
  end if;

  return v_row;
end;
$$;

create or replace function public.registre_integrity_check(
  p_year integer,
  p_register_type text
)
returns jsonb
language sql
security definer
set search_path = public, pg_temp
as $$
  with d as (
    select official_number, count(*) as c
    from public.registre_entries
    where year = p_year and register_type = p_register_type
    group by official_number
    having count(*) > 1
  )
  select jsonb_build_object(
    'ok', not exists(select 1 from d),
    'duplicateCount', (select count(*) from d)
  );
$$;

revoke all on function public.registre_cancel_entry(uuid,text,text) from public;
revoke all on function public.registre_delete_entry(uuid,text) from public;
revoke all on function public.registre_integrity_check(integer,text) from public;
grant execute on function public.registre_cancel_entry(uuid,text,text) to anon, authenticated;
grant execute on function public.registre_delete_entry(uuid,text) to anon, authenticated;
grant execute on function public.registre_integrity_check(integer,text) to anon, authenticated;
