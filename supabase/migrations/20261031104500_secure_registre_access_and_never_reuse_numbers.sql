-- Sécurise Registre Communautaire sans modifier les autres applications du projet Supabase.
-- Les numéros déjà utilisés restent définitivement réservés, y compris après annulation/suppression.

create table if not exists public.registre_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null default '',
  role text not null check (role in ('admin','agent','viewer')),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.registre_members enable row level security;
revoke all on table public.registre_members from anon, authenticated;

create or replace function public.registre_member_role()
returns text
language sql
stable
security definer
set search_path = public, pg_temp
as $$
  select m.role
  from public.registre_members m
  where m.user_id = auth.uid()
    and m.active = true
  limit 1;
$$;

revoke all on function public.registre_member_role() from public, anon;
grant execute on function public.registre_member_role() to authenticated;

create or replace function public.registre_claim_access()
returns jsonb
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_email text := coalesce(auth.jwt()->>'email', '');
  v_role text;
  v_bootstrap boolean := false;
begin
  if v_uid is null then
    raise exception 'Authentification requise';
  end if;

  select role into v_role
  from public.registre_members
  where user_id = v_uid and active = true;

  if v_role is null then
    lock table public.registre_members in share row exclusive mode;

    if not exists (select 1 from public.registre_members where active = true) then
      insert into public.registre_members(user_id, email, role, active)
      values (v_uid, v_email, 'admin', true)
      on conflict (user_id) do update
      set email = excluded.email,
          role = 'admin',
          active = true,
          updated_at = now();
      v_role := 'admin';
      v_bootstrap := true;
    end if;
  end if;

  return jsonb_build_object(
    'approved', v_role is not null,
    'role', coalesce(v_role, ''),
    'userId', v_uid::text,
    'email', v_email,
    'bootstrapAdmin', v_bootstrap
  );
end;
$$;

revoke all on function public.registre_claim_access() from public, anon;
grant execute on function public.registre_claim_access() to authenticated;

create or replace function public.registre_add_member_by_email(
  p_email text,
  p_role text default 'agent'
)
returns jsonb
language plpgsql
security definer
set search_path = public, auth, pg_temp
as $$
declare
  v_uid uuid;
  v_email text := lower(trim(coalesce(p_email, '')));
begin
  if public.registre_member_role() <> 'admin' then
    raise exception 'Seul un administrateur peut gérer les accès';
  end if;

  if p_role not in ('admin','agent','viewer') then
    raise exception 'Rôle invalide';
  end if;

  select id into v_uid
  from auth.users
  where lower(email) = v_email
  limit 1;

  if v_uid is null then
    raise exception 'Compte Supabase introuvable pour cet email';
  end if;

  insert into public.registre_members(user_id, email, role, active)
  values (v_uid, v_email, p_role, true)
  on conflict (user_id) do update
  set email = excluded.email,
      role = excluded.role,
      active = true,
      updated_at = now();

  return jsonb_build_object('ok', true, 'userId', v_uid::text, 'role', p_role);
end;
$$;

revoke all on function public.registre_add_member_by_email(text,text) from public, anon;
grant execute on function public.registre_add_member_by_email(text,text) to authenticated;

-- La lecture du registre est réservée aux membres explicitement autorisés.
drop policy if exists "registre lecture communautaire" on public.registre_entries;
drop policy if exists "registre lecture membres" on public.registre_entries;
create policy "registre lecture membres"
on public.registre_entries
for select
to authenticated
using (public.registre_member_role() is not null);

revoke all on table public.registre_entries from anon;
grant select on table public.registre_entries to authenticated;

-- Les compteurs et numéros recyclés restent inaccessibles directement aux clients.
revoke all on table public.registre_counters from anon, authenticated;
revoke all on table public.registre_recycled_numbers from anon, authenticated;

-- Conserve les implémentations métier existantes derrière des wrappers d'autorisation.
alter function public.registre_create_entry(jsonb) rename to registre_create_entry_impl_20261006;
alter function public.registre_cancel_entry(uuid,text,text) rename to registre_cancel_entry_impl_20261006;
alter function public.registre_delete_entry(uuid,text) rename to registre_delete_entry_impl_20261006;
alter function public.registre_close_movement(uuid,text) rename to registre_close_movement_impl_20261006;
alter function public.registre_counter_status(integer,text) rename to registre_counter_status_impl_20261006;
alter function public.registre_initialize_counter(integer,text,bigint) rename to registre_initialize_counter_impl_20261006;
alter function public.registre_integrity_check(integer,text) rename to registre_integrity_check_impl_20261006;

revoke all on function public.registre_create_entry_impl_20261006(jsonb) from public, anon, authenticated;
revoke all on function public.registre_cancel_entry_impl_20261006(uuid,text,text) from public, anon, authenticated;
revoke all on function public.registre_delete_entry_impl_20261006(uuid,text) from public, anon, authenticated;
revoke all on function public.registre_close_movement_impl_20261006(uuid,text) from public, anon, authenticated;
revoke all on function public.registre_counter_status_impl_20261006(integer,text) from public, anon, authenticated;
revoke all on function public.registre_initialize_counter_impl_20261006(integer,text,bigint) from public, anon, authenticated;
revoke all on function public.registre_integrity_check_impl_20261006(integer,text) from public, anon, authenticated;
revoke all on function public.registre_recompute_counter(integer,text) from public, anon, authenticated;

create or replace function public.registre_create_entry(p_payload jsonb)
returns public.registre_entries
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_role text := public.registre_member_role();
  v_year integer;
  v_type text;
  v_historical_max bigint;
  v_row public.registre_entries;
begin
  if v_role not in ('admin','agent') then
    raise exception 'Accès en écriture refusé';
  end if;

  v_year := coalesce(nullif(p_payload->>'year','')::integer,
    extract(year from timezone('Indian/Antananarivo', now()))::integer);
  v_type := p_payload->>'registerType';

  select coalesce(max(official_number), 0)
  into v_historical_max
  from public.registre_entries
  where year = v_year and register_type = v_type;

  -- Les anciens numéros libérés ne doivent plus être réutilisés.
  delete from public.registre_recycled_numbers
  where year = v_year and register_type = v_type;

  update public.registre_counters
  set last_number = greatest(last_number, v_historical_max),
      updated_at = now()
  where year = v_year and register_type = v_type;

  v_row := public.registre_create_entry_impl_20261006(p_payload);
  return v_row;
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
  v_role text := public.registre_member_role();
  v_owner uuid;
  v_device text;
  v_row public.registre_entries;
begin
  if v_role not in ('admin','agent') then
    raise exception 'Accès en écriture refusé';
  end if;

  select created_by, creator_device_id
  into v_owner, v_device
  from public.registre_entries
  where id = p_entry_id;

  if v_role <> 'admin' and v_owner is distinct from auth.uid() then
    raise exception 'Vous ne pouvez annuler que vos propres entrées';
  end if;

  v_row := public.registre_cancel_entry_impl_20261006(p_entry_id, coalesce(v_device,''), p_reason);

  delete from public.registre_recycled_numbers
  where year = v_row.year
    and register_type = v_row.register_type
    and official_number = v_row.official_number;

  update public.registre_counters c
  set last_number = greatest(
        c.last_number,
        coalesce((
          select max(e.official_number)
          from public.registre_entries e
          where e.year = v_row.year and e.register_type = v_row.register_type
        ), 0)
      ),
      updated_at = now()
  where c.year = v_row.year and c.register_type = v_row.register_type;

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
  v_role text := public.registre_member_role();
  v_owner uuid;
  v_device text;
  v_row public.registre_entries;
begin
  if v_role not in ('admin','agent') then
    raise exception 'Accès en écriture refusé';
  end if;

  select created_by, creator_device_id
  into v_owner, v_device
  from public.registre_entries
  where id = p_entry_id;

  if v_role <> 'admin' and v_owner is distinct from auth.uid() then
    raise exception 'Vous ne pouvez supprimer que vos propres entrées';
  end if;

  v_row := public.registre_delete_entry_impl_20261006(p_entry_id, coalesce(v_device,''));

  delete from public.registre_recycled_numbers
  where year = v_row.year
    and register_type = v_row.register_type
    and official_number = v_row.official_number;

  update public.registre_counters c
  set last_number = greatest(
        c.last_number,
        coalesce((
          select max(e.official_number)
          from public.registre_entries e
          where e.year = v_row.year and e.register_type = v_row.register_type
        ), 0)
      ),
      updated_at = now()
  where c.year = v_row.year and c.register_type = v_row.register_type;

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
  v_role text := public.registre_member_role();
  v_owner uuid;
  v_device text;
begin
  if v_role not in ('admin','agent') then
    raise exception 'Accès en écriture refusé';
  end if;

  select created_by, creator_device_id
  into v_owner, v_device
  from public.registre_entries
  where id = p_entry_id;

  if v_role <> 'admin' and v_owner is distinct from auth.uid() then
    raise exception 'Vous ne pouvez clôturer que vos propres déplacements';
  end if;

  return public.registre_close_movement_impl_20261006(p_entry_id, coalesce(v_device,''));
end;
$$;

create or replace function public.registre_counter_status(
  p_year integer,
  p_register_type text
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
  v_exists boolean;
  v_last bigint;
  v_historical_max bigint;
begin
  if public.registre_member_role() is null then
    raise exception 'Accès au registre refusé';
  end if;

  if p_register_type not in ('/2','/3','/4','/3.S','/3.PERM') then
    raise exception 'Type de registre invalide';
  end if;

  select exists(
    select 1 from public.registre_counters
    where year = p_year and register_type = p_register_type
  ) into v_exists;

  select coalesce(max(official_number), 0)
  into v_historical_max
  from public.registre_entries
  where year = p_year and register_type = p_register_type;

  if v_exists then
    update public.registre_counters
    set last_number = greatest(last_number, v_historical_max),
        updated_at = now()
    where year = p_year and register_type = p_register_type
    returning last_number into v_last;
  else
    v_last := v_historical_max;
  end if;

  return jsonb_build_object(
    'initialized', v_exists,
    'lastNumber', coalesce(v_last,0),
    'nextNumber', coalesce(v_last,0) + 1
  );
end;
$$;

create or replace function public.registre_initialize_counter(
  p_year integer,
  p_register_type text,
  p_last_used bigint default 0
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if public.registre_member_role() <> 'admin' then
    raise exception 'Seul un administrateur peut initialiser un compteur';
  end if;

  perform public.registre_initialize_counter_impl_20261006(
    p_year, p_register_type, p_last_used
  );
  return public.registre_counter_status(p_year, p_register_type);
end;
$$;

create or replace function public.registre_integrity_check(
  p_year integer,
  p_register_type text
)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
  if public.registre_member_role() is null then
    raise exception 'Accès au registre refusé';
  end if;
  return public.registre_integrity_check_impl_20261006(p_year, p_register_type);
end;
$$;

revoke all on function public.registre_create_entry(jsonb) from public, anon;
revoke all on function public.registre_cancel_entry(uuid,text,text) from public, anon;
revoke all on function public.registre_delete_entry(uuid,text) from public, anon;
revoke all on function public.registre_close_movement(uuid,text) from public, anon;
revoke all on function public.registre_counter_status(integer,text) from public, anon;
revoke all on function public.registre_initialize_counter(integer,text,bigint) from public, anon;
revoke all on function public.registre_integrity_check(integer,text) from public, anon;

grant execute on function public.registre_create_entry(jsonb) to authenticated;
grant execute on function public.registre_cancel_entry(uuid,text,text) to authenticated;
grant execute on function public.registre_delete_entry(uuid,text) to authenticated;
grant execute on function public.registre_close_movement(uuid,text) to authenticated;
grant execute on function public.registre_counter_status(integer,text) to authenticated;
grant execute on function public.registre_initialize_counter(integer,text,bigint) to authenticated;
grant execute on function public.registre_integrity_check(integer,text) to authenticated;
