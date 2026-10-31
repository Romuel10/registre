# Registre Communautaire

Application Android de numérotation administrative partagée pour éviter les doublons entre plusieurs utilisateurs et plusieurs téléphones.

## Version

Version Android : **1.6.0**.

## Cahiers gérés

- `/2`
- `/3`
- `/4`
- `/3.S`
- `/3.PERM`

Les années précédentes restent consultables et sont considérées comme clôturées.

## Sécurité et accès

Depuis la version 1.6.0, l'accès au registre nécessite une session **Supabase Auth**.

Trois rôles sont disponibles :

- `admin` : lecture, écriture, initialisation des compteurs et gestion des membres ;
- `agent` : lecture et écriture ;
- `viewer` : lecture seule.

Les données du registre ne sont plus lisibles par le rôle Supabase `anon`.

Le premier compte authentifié qui réclame l'accès après l'application de la migration de sécurité devient administrateur si aucun membre n'existe encore. Les comptes suivants doivent être ajoutés par un administrateur.

Les jetons d'authentification et les brouillons hors connexion sont stockés localement avec chiffrement AES/GCM adossé à Android Keystore. La sauvegarde Android de l'application est désactivée afin d'éviter l'export involontaire de données sensibles.

## Numérotation

Le numéro officiel est attribué uniquement par PostgreSQL.

Pour un cahier encore vide dans l'application, le premier ajout demande le dernier numéro déjà utilisé dans le cahier réel :

- si le dernier numéro est `50`, le prochain sera `51` ;
- si le cahier commence dans l'application, l'administrateur peut choisir un départ à `1`.

Pour les valeurs historiques configurées en 2026 :

- `/2` : dernier numéro initial = `281/2` ;
- `/3.PERM` : dernier numéro initial = `18/3.PERM`.

### Les numéros ne sont jamais réutilisés

À partir de la version 1.6.0, un numéro attribué reste définitivement réservé, même si l'entrée est ensuite annulée ou supprimée de la liste active.

Exemple : si `295/2` est annulé ou supprimé, un nouvel enregistrement ne doit jamais reprendre `295/2`.

La base existante contient un ancien doublon historique sur `295/2` issu de l'ancien mécanisme de recyclage : une ancienne ligne supprimée et une ligne active. La migration 1.6.0 conserve cet historique au lieu de le falsifier, mais empêche toute nouvelle réutilisation.

## Contrôle des doublons

Avant toute création :

1. Supabase vérifie l'intégrité du cahier et de l'année ;
2. le compteur est verrouillé transactionnellement ;
3. le prochain numéro est calculé à partir du compteur et des numéros historiques ;
4. les anciens numéros libérés ne sont plus remis en circulation ;
5. les contraintes PostgreSQL continuent de protéger les entrées actives contre les doublons.

L'application bloque aussi la création si une anomalie d'intégrité est détectée.

## Annulation et suppression

Une entrée peut être annulée ou supprimée de l'affichage sans effacer son historique technique.

- un `agent` peut modifier uniquement les entrées dont il est le créateur authentifié ;
- un `admin` peut administrer les entrées ;
- un `viewer` est en lecture seule.

Le paramètre historique `creator_device_id` n'est plus utilisé comme preuve d'identité pour les autorisations serveur. L'autorisation repose sur `auth.uid()` et le rôle du membre.

## /2 et /4 : déplacements et disponibilités

Les cahiers `/2` et `/4` proposent :

- pièce ordinaire ;
- message de déplacement ;
- message de disponibilité.

Une disponibilité peut être simple ou liée à un déplacement. Lorsqu'elle est liée, un déplacement actif ne peut recevoir qu'une seule disponibilité active.

### Déplacement permission relié au /3.PERM

Lorsqu'un `déplacement perm` est créé dans `/2` ou `/4`, l'application propose les permissions `/3.PERM` encore sans message de déplacement actif.

Après sélection :

- le nom, le matricule et le grade servent de contrôle visuel ;
- la date de départ et la durée proviennent du `/3.PERM` ;
- la date d'arrivée est recalculée automatiquement ;
- la relation technique entre le déplacement et la permission est conservée ;
- une même permission ne peut pas produire deux déplacements actifs.

## /3.PERM

Pour une permission :

- la date de départ est choisie dans le calendrier Android ;
- la durée est saisie en jours ou déclarée indéterminée ;
- le droit année accepte une année telle que `2026` ;
- le droit consommé accepte un texte tel que `2025-20 jours`.

La règle de calcul est :

`date_arrivée = date_départ + (nombre_de_jours - 1)`

Le jour du départ compte comme jour 1.

## Rappels et mode hors connexion

WorkManager assure les rappels et la synchronisation lorsque le réseau revient.

Si Supabase n'est pas joignable pendant une saisie :

1. l'entrée reste en attente localement ;
2. aucun faux numéro officiel n'est généré ;
3. les données en attente sont chiffrées localement ;
4. WorkManager synchronise après le retour du réseau et uniquement si une session authentifiée existe ;
5. Supabase attribue alors le prochain numéro officiel.

Les vérifications réseau en premier plan ont été espacées pour limiter la consommation de batterie et de données mobiles.

## Backend Supabase

Projet actuel : `Romuel-apps`.

Tables principales :

- `public.registre_entries`
- `public.registre_counters`
- `public.registre_recycled_numbers` — conservée pour compatibilité historique, mais le recyclage n'est plus utilisé ;
- `public.registre_members` — membres et rôles de Registre Communautaire.

Fonctions clientes principales :

- `public.registre_claim_access()`
- `public.registre_add_member_by_email(text,text)`
- `public.registre_create_entry(jsonb)`
- `public.registre_cancel_entry(uuid,text,text)`
- `public.registre_delete_entry(uuid,text)`
- `public.registre_close_movement(uuid,text)`
- `public.registre_integrity_check(integer,text)`
- `public.registre_counter_status(integer,text)`
- `public.registre_initialize_counter(integer,text,bigint)`

Les anciennes implémentations métier sont conservées derrière des wrappers de sécurité et ne sont plus exécutables directement par `anon` ou `authenticated`.

## Interface

- Kotlin
- Jetpack Compose
- Material 3
- réglage de la taille du texte
- écran de démarrage animé
- calendrier Android
- dates visibles au format `JJ-MM-AAAA`
- écran de connexion
- gestion des rôles par l'administrateur

## Construction

JDK 17 et Android SDK requis.

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

APK généré :

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions exécute les tests unitaires, Android Lint puis construit automatiquement l'APK.

## Ordre de déploiement de la version 1.6.0

Pour éviter de couper l'accès aux anciennes versions :

1. fusionner et laisser GitHub Actions valider le code 1.6.0 ;
2. récupérer l'APK 1.6.0 ;
3. appliquer la migration `20261031104500_secure_registre_access_and_never_reuse_numbers.sql` sur Supabase ;
4. installer immédiatement l'APK 1.6.0 ;
5. connecter le premier compte autorisé afin qu'il devienne administrateur ;
6. ajouter les autres comptes depuis le panneau `Accès`.

L'ancienne version utilisant des appels anonymes ne pourra plus accéder aux données après l'application de la migration de sécurité.
