-- Corrige une fausse alerte d'integrite lorsqu'un numero historique supprime
-- a ete reutilise avant la politique de reservation definitive.
-- Les doublons parmi les entrees actives bloquent toujours les saisies.
-- Les doublons historiques restent visibles dans historicalDuplicateCount.
create or replace function public.registre_integrity_check_impl_20261006(
  p_year integer, p_register_type text
)
returns jsonb
language sql stable security definer
set search_path = public, pg_temp
as $function$
  with active_duplicates as (
    select official_number
    from public.registre_entries
    where year = p_year
      and register_type = p_register_type
      and status = 'NUMBERED'
      and deleted_at is null
      and official_number is not null
    group by official_number
    having count(*) > 1
  ),
  historical_duplicates as (
    select official_number
    from public.registre_entries
    where year = p_year
      and register_type = p_register_type
      and official_number is not null
    group by official_number
    having count(*) > 1
  )
  select jsonb_build_object(
    'ok', not exists(select 1 from active_duplicates),
    'duplicateCount', (select count(*) from active_duplicates),
    'historicalDuplicateCount', (select count(*) from historical_duplicates),
    'maxNumber', coalesce((
      select max(official_number)
      from public.registre_entries
      where year = p_year and register_type = p_register_type
    ), 0),
    'counter', coalesce((
      select last_number
      from public.registre_counters
      where year = p_year and register_type = p_register_type
    ), 0)
  );
$function$;

revoke all on function public.registre_integrity_check_impl_20261006(integer,text)
  from public, anon, authenticated;
