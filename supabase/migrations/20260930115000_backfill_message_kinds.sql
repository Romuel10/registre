-- Compatibilité avec les entrées créées avant l'introduction du workflow disponibilité.

update public.registre_entries
set message_kind = 'movement'
where register_type = '/2'
  and movement_kind like 'déplacement%'
  and message_kind in ('','ordinary');

update public.registre_entries
set message_kind = 'permission'
where register_type = '/3.PERM'
  and message_kind in ('','ordinary');
