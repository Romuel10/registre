-- Autorise les messages de disponibilité simples dans /2 et /4.
-- related_movement_id reste optionnel. Lorsqu'il est renseigné, les contrôles
-- de cohérence et d'unicité du déplacement restent inchangés.

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
  v_movement_kind text;
  v_related_movement uuid;
  v_related_permission uuid;
  v_duplicate_count integer;

  v_beneficiary text;
  v_departure_date date;
  v_arrival_date date;
  v_duration_days integer;

  v_perm public.registre_entries;
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
  v_movement_kind := coalesce(p_payload->>'movementKind','');
  v_related_movement := nullif(p_payload->>'relatedMovementId','')::uuid;
  v_related_permission := nullif(p_payload->>'relatedPermissionId','')::uuid;

  v_beneficiary := coalesce(p_payload->>'beneficiary','');
  v_departure_date := nullif(p_payload->>'departureDate','')::date;
  v_arrival_date := nullif(p_payload->>'arrivalDate','')::date;
  v_duration_days := coalesce(nullif(p_payload->>'durationDays','')::integer, 0);

  if v_type is null or v_type not in ('/2','/3','/4','/3.S','/3.PERM') then
    raise exception 'Type de registre invalide';
  end if;

  if v_year <> v_current_year then
    raise exception 'Cette année est clôturée et reste en lecture seule';
  end if;

  if not exists (
    select 1
    from public.registre_counters
    where year = v_year
      and register_type = v_type
  ) then
    if exists (
      select 1
      from public.registre_entries
      where year = v_year
        and register_type = v_type
    ) then
      insert into public.registre_counters(
        year, register_type, last_number, initial_last_number, updated_at
      )
      select
        v_year,
        v_type,
        coalesce(max(official_number), 0),
        0,
        now()
      from public.registre_entries
      where year = v_year
        and register_type = v_type
      on conflict (year, register_type) do nothing;
    else
      raise exception 'Initialisez d''abord la numérotation de % en indiquant le dernier numéro déjà utilisé ou en choisissant début à 1.', v_type;
    end if;
  end if;

  select count(*) into v_duplicate_count
  from (
    select official_number
    from public.registre_entries
    where year = v_year
      and register_type = v_type
      and status = 'NUMBERED'
      and deleted_at is null
    group by official_number
    having count(*) > 1
  ) d;

  if v_duplicate_count > 0 then
    raise exception 'Doublon de numéro détecté dans %. Enregistrement bloqué.', v_type;
  end if;

  if v_type = '/3.PERM'
     and not v_indefinite
     and v_duration_days > 0
     and v_departure_date is not null then
    v_arrival_date := v_departure_date + (v_duration_days - 1);
  end if;

  if v_movement_kind = 'déplacement perm' then
    if v_type not in ('/2','/4') then
      raise exception 'Le déplacement permission doit être enregistré dans /2 ou /4';
    end if;

    if v_related_permission is null then
      raise exception 'Sélectionnez la permission /3.PERM concernée';
    end if;

    select *
    into v_perm
    from public.registre_entries
    where id = v_related_permission
      and register_type = '/3.PERM'
      and status = 'NUMBERED'
      and deleted_at is null;

    if v_perm.id is null then
      raise exception 'Permission /3.PERM introuvable, annulée ou supprimée';
    end if;

    if exists (
      select 1
      from public.registre_entries m
      where m.related_permission_id = v_related_permission
        and m.message_kind = 'movement'
        and m.register_type in ('/2','/4')
        and m.status = 'NUMBERED'
        and m.deleted_at is null
    ) then
      raise exception 'Cette permission possède déjà un message de déplacement';
    end if;

    v_beneficiary := v_perm.full_name;
    v_departure_date := v_perm.departure_date;
    v_duration_days := v_perm.duration_days;
    v_indefinite := v_perm.duration_indefinite;

    if v_indefinite then
      v_arrival_date := null;
      v_duration_days := 0;
    elsif v_departure_date is not null and v_perm.duration_days > 0 then
      v_arrival_date := v_departure_date + (v_perm.duration_days - 1);
    else
      v_arrival_date := v_perm.arrival_date;
    end if;
  end if;

  if v_message_kind = 'availability' then
    if v_type not in ('/2','/4') then
      raise exception 'Le message de disponibilité doit être enregistré dans /2 ou /4';
    end if;

    -- Deux modes sont autorisés :
    -- 1) disponibilité simple : aucun déplacement lié ;
    -- 2) disponibilité liée : le déplacement doit être valide et encore ouvert.
    if v_related_movement is not null then
      if not exists (
        select 1
        from public.registre_entries m
        where m.id = v_related_movement
          and m.register_type = v_type
          and m.message_kind = 'movement'
          and m.status = 'NUMBERED'
          and m.deleted_at is null
      ) then
        raise exception 'Message de déplacement introuvable, annulé ou appartenant à un autre cahier';
      end if;

      if exists (
        select 1
        from public.registre_entries a
        where a.related_movement_id = v_related_movement
          and a.message_kind = 'availability'
          and a.status = 'NUMBERED'
          and a.deleted_at is null
      ) then
        raise exception 'Un message de disponibilité existe déjà pour ce déplacement';
      end if;
    end if;
  end if;

  if v_indefinite = false
     and v_duration_days > 0
     and v_arrival_date is null then
    raise exception 'Date d''arrivée requise pour une durée déterminée';
  end if;

  select last_number into v_counter
  from public.registre_counters
  where year = v_year and register_type = v_type
  for update;

  select coalesce(max(official_number), 0) into v_max
  from public.registre_entries
  where year = v_year
    and register_type = v_type
    and status = 'NUMBERED'
    and deleted_at is null;

  delete from public.registre_recycled_numbers
  where (year, register_type, official_number) = (
    select year, register_type, official_number
    from public.registre_recycled_numbers
    where year = v_year
      and register_type = v_type
    order by official_number
    limit 1
    for update skip locked
  )
  returning official_number into v_number;

  if v_number is null then
    v_number := greatest(coalesce(v_counter, 0) + 1, v_max + 1);
  end if;

  update public.registre_counters
  set last_number = greatest(last_number, v_number),
      updated_at = now()
  where year = v_year and register_type = v_type;

  if exists (
    select 1
    from public.registre_entries
    where year = v_year
      and register_type = v_type
      and official_number = v_number
      and status = 'NUMBERED'
      and deleted_at is null
  ) then
    raise exception 'Numéro % déjà utilisé dans %. Enregistrement bloqué.', v_number, v_type;
  end if;

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
    v_departure_date,
    case when v_indefinite then null else v_arrival_date end,
    0,
    0,
    v_annual_right_year,
    coalesce(p_payload->>'consumedRightDetail', p_payload->>'consumedRight', ''),
    v_movement_kind,
    case when v_indefinite then 0 else v_duration_days end,
    v_indefinite,
    case
      when coalesce(nullif(p_payload->>'movementClosed','')::boolean, false)
      then now()
      else null
    end,
    v_beneficiary,
    coalesce(p_payload->>'creatorDeviceId',''),
    case
      when v_type = '/3.PERM' then 'permission'
      when v_message_kind <> '' then v_message_kind
      when v_type in ('/2','/4') and v_movement_kind like 'déplacement%' then 'movement'
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
    raise exception 'Doublon bloqué : ce numéro, cette permission ou ce message de disponibilité est déjà utilisé';
end;
$$;

revoke all on function public.registre_create_entry(jsonb) from public;
grant execute on function public.registre_create_entry(jsonb) to anon, authenticated;
