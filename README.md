# Registre Communautaire

Application Android pour partager la numérotation des cahiers administratifs et éviter les doublons entre utilisateurs.

## Cahiers gérés

- /2
- /3
- /4
- /3.S
- /3.PERM

Chaque nouvelle année utilise une séquence indépendante qui repart automatiquement à 1. Les années précédentes restent consultables et sont considérées comme clôturées.

## Backend Supabase

L'application utilise **Supabase / PostgreSQL**.

Projet utilisé :
- nom : `Romuel-apps`
- project ref : `gmlofgsgnbbcbefogpww`

Tables principales :
- `public.registre_entries`
- `public.registre_counters`

Fonctions principales :
- `public.registre_create_entry(jsonb)`
- `public.registre_cancel_entry(uuid,text,text)`
- `public.registre_delete_entry(uuid,text)`
- `public.registre_integrity_check(integer,text)`

## Contrôle des doublons

Le numéro officiel est attribué uniquement par PostgreSQL.

Avant toute création :
1. Supabase vérifie si un doublon existe déjà dans le cahier et l'année concernés ;
2. si un doublon est détecté, l'enregistrement est bloqué ;
3. le compteur est verrouillé transactionnellement ;
4. le prochain numéro est calculé à partir du compteur **et** du plus grand numéro réellement présent ;
5. une contrainte unique protège encore `(year, register_type, official_number)`.

L'application effectue aussi un contrôle d'intégrité et désactive le bouton de création si une anomalie de numérotation est détectée.

## Annulation et suppression

Une entrée créée sur le téléphone peut être :
- **annulée** : elle reste visible avec son numéro et son motif d'annulation ;
- **supprimée de l'affichage** : la ligne reste conservée côté base pour audit, mais n'apparaît plus dans la liste active.

Dans les deux cas, **le numéro n'est jamais réutilisé**.

Un brouillon local encore sans numéro peut être supprimé directement.

## /2 : déplacement et disponibilité

Le cahier /2 propose trois natures :
- pièce ordinaire ;
- message de déplacement ;
- message de disponibilité.

Quand l'utilisateur choisit **Message de disponibilité**, l'application affiche la liste des messages de déplacement qui n'ont pas encore reçu de disponibilité. Le message choisi est lié au nouveau message de disponibilité.

Un déplacement ne peut recevoir qu'un seul message de disponibilité actif.

## /3.PERM

Pour une permission :
- la date de départ se choisit dans un calendrier Android ;
- le nombre de jours est saisi au clavier ;
- le droit année accepte une année telle que `2026` ou `2025` ;
- le droit consommé accepte un texte tel que `2025-20 jours` ;
- une durée peut être déclarée indéterminée.

Après chaque enregistrement /3.PERM, l'application rappelle immédiatement de créer dans **/2** le message de déplacement **« déplacement perm »**.

À la fin de la permission, l'application rappelle de créer dans **/2** le message de disponibilité **« disponibilité perm »**.

## Rappels hors connexion

Pour une durée déterminée :

`date_arrivée = date_départ + (nombre_de_jours - 1)`

Le jour du départ compte comme **jour 1**.

Pour une durée indéterminée, Android programme un rappel local tous les 3 jours jusqu'à confirmation du retour.

Ces rappels reposent sur WorkManager et ne nécessitent pas de connexion Internet une fois programmés.

## Notifications communautaires

Lorsqu'un autre appareil crée un nouveau /2, /3, /4, /3.S ou /3.PERM :
- l'application ouverte vérifie rapidement les nouvelles entrées ;
- en arrière-plan, WorkManager effectue une vérification périodique avec connexion réseau ;
- une notification Android informe l'utilisateur.

Le téléphone créateur ne se notifie pas lui-même.

## Interface

- Jetpack Compose + Material 3
- cartes et formulaires modernisés
- réglage de la taille du texte
- écran de démarrage animé
- calendrier Android pour les dates
- menu d'actions pour annuler ou supprimer une entrée

## Mode hors ligne

Si Supabase n'est pas joignable pendant une saisie ordinaire :
1. l'entrée reste localement en `PENDING_NUMBER` ;
2. aucun faux numéro officiel n'est inventé ;
3. WorkManager attend le retour du réseau ;
4. Supabase attribue ensuite le prochain numéro officiel disponible.

## Sécurité

- Row Level Security est activé sur les tables du registre.
- Les clients ne modifient pas directement les compteurs.
- La numérotation passe par une fonction PostgreSQL transactionnelle.
- L'application utilise uniquement une **publishable key** Supabase.
- Aucune clé `service_role` ou clé secrète n'est incluse dans l'APK.

## Version

Version Android actuelle : **1.3.0**

## Construction

JDK 17 et Android SDK requis.

```bash
gradle :app:assembleDebug
```

APK :

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions exécute les tests unitaires puis construit automatiquement l'APK.
