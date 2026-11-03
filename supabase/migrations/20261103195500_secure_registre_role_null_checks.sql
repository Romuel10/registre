-- Éviter un contournement des rôles : en PL/pgSQL, IF NULL n'entre pas
-- dans la branche IF et ne refuse donc pas l'accès.
-- Exiger explicitement admin/agent ou admin pour toutes les RPC d'écriture.
DO $migration$
DECLARE
  f record;
  old_definition text;
  new_definition text;
  corrected int := 0;
BEGIN
  FOR f IN
    SELECT p.oid, p.proname
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname IN (
        'registre_add_member_by_email',
        'registre_cancel_entry',
        'registre_close_movement',
        'registre_create_entry',
        'registre_delete_entry',
        'registre_initialize_counter'
      )
  LOOP
    old_definition := pg_get_functiondef(f.oid);
    new_definition := replace(
      old_definition,
      'if v_role not in (''admin'',''agent'') then',
      'if coalesce(v_role, '''') not in (''admin'',''agent'') then'
    );
    new_definition := replace(
      new_definition,
      'if public.registre_member_role() <> ''admin'' then',
      'if coalesce(public.registre_member_role(), '''') <> ''admin'' then'
    );
    IF new_definition = old_definition THEN
      RAISE EXCEPTION 'Vérification des droits introuvable: %', f.proname;
    END IF;
    EXECUTE new_definition;
    corrected := corrected + 1;
  END LOOP;
  IF corrected <> 6 THEN
    RAISE EXCEPTION '6 fonctions attendues, % modifiées', corrected;
  END IF;
END $migration$;