-- Empêche un utilisateur d'une autre application partageant le même projet Supabase
-- de devenir administrateur du Registre simplement en se connectant en premier.
-- Le code d'amorçage est injecté côté serveur après déploiement sous forme de hash SHA-256.

create table if not exists public.registre_admin_bootstrap (
  singleton boolean primary key default true check (singleton = true),
  code_hash text not null default '',
  used_at timestamptz,
  used_by uuid references auth.users(id),
  updated_at timestamptz not null default now()
);

alter table public.registre_admin_bootstrap enable row level security;
revoke all on table public.registre_admin_bootstrap from public, anon, authenticated;

-- La consultation de l'accès ne crée plus automatiquement un administrateur.
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
  v_bootstrap_required boolean;
begin
  if v_uid is null then
    raise exception 'Authentification requise';
  end if;

  select role into v_role
  from public.registre_members
  where user_id = v_uid and active = true;

  select not exists (
    select 1 from public.registre_members where active = true
  ) into v_bootstrap_required;

  return jsonb_build_object(
    'approved', v_role is not null,
    'role', coalesce(v_role, ''),
    'userId', v_uid::text,
    'email', v_email,
    'bootstrapRequired', v_bootstrap_required
  );
end;
$$;

revoke all on function public.registre_claim_access() from public, anon;
grant execute on function public.registre_claim_access() to authenticated;

create or replace function public.registre_claim_admin(p_bootstrap_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, auth, extensions, pg_temp
as $$
declare
  v_uid uuid := auth.uid();
  v_email text := coalesce(auth.jwt()->>'email', '');
  v_hash text;
  v_used_at timestamptz;
begin
  if v_uid is null then
    raise exception 'Authentification requise';
  end if;

  -- Une seule initialisation peut gagner la course.
  lock table public.registre_members in share row exclusive mode;

  if exists (select 1 from public.registre_members where active = true) then
    raise exception 'Le premier administrateur est déjà configuré';
  end if;

  insert into public.registre_admin_bootstrap(singleton)
  values (true)
  on conflict (singleton) do nothing;

  select code_hash, used_at
  into v_hash, v_used_at
  from public.registre_admin_bootstrap
  where singleton = true
  for update;

  if v_used_at is not null then
    raise exception 'Le code d''amorçage a déjà été utilisé';
  end if;

  if coalesce(v_hash, '') = '' then
    raise exception 'Code d''amorçage non configuré côté serveur';
  end if;

  if encode(digest(coalesce(p_bootstrap_code, ''), 'sha256'), 'hex') <> v_hash then
    raise exception 'Code d''amorçage incorrect';
  end if;

  insert into public.registre_members(user_id, email, role, active)
  values (v_uid, v_email, 'admin', true)
  on conflict (user_id) do update
  set email = excluded.email,
      role = 'admin',
      active = true,
      updated_at = now();

  update public.registre_admin_bootstrap
  set used_at = now(),
      used_by = v_uid,
      updated_at = now()
  where singleton = true;

  return jsonb_build_object(
    'approved', true,
    'role', 'admin',
    'userId', v_uid::text,
    'email', v_email,
    'bootstrapRequired', false
  );
end;
$$;

revoke all on function public.registre_claim_admin(text) from public, anon;
grant execute on function public.registre_claim_admin(text) to authenticated;
