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

L'application utilise maintenant **Supabase / PostgreSQL** au lieu de Firebase.

Projet utilisé :
- nom : `Romuel-apps`
- project ref : `gmlofgsgnbbcbefogpww`

Tables :
- `public.registre_entries`
- `public.registre_counters`

Fonction transactionnelle :
- `public.registre_create_entry(jsonb)`

La migration est versionnée dans :

`supabase/migrations/20260930102300_create_registre_community_backend.sql`

## Garantie d'unicité des numéros

Le numéro officiel n'est pas calculé par le téléphone.

À chaque enregistrement, PostgreSQL exécute une fonction transactionnelle qui incrémente atomiquement le compteur correspondant à :

`année + type de cahier`

Une contrainte unique protège également :

`(year, register_type, official_number)`

Deux téléphones ne peuvent donc pas recevoir le même numéro officiel.

## Mode hors ligne

Si Supabase n'est pas joignable au moment de la saisie :

1. l'entrée est conservée localement comme `PENDING_NUMBER` ;
2. aucun faux numéro officiel n'est inventé ;
3. WorkManager attend le retour du réseau ;
4. le brouillon est envoyé à Supabase ;
5. Supabase lui attribue alors le prochain numéro officiel disponible.

## Rappels déplacement / permission

Pour un déplacement ou une permission, le jour du départ compte comme **jour 1** :

`date_arrivée = date_départ + (nombre_de_jours - 1)`

Exemple : départ aujourd'hui pour 2 jours = aujourd'hui + demain, arrivée demain.

Le rappel local Android reste géré par WorkManager et fonctionne sans Firebase.

## Notifications communautaires

Chaque téléphone possède un identifiant local. Lorsqu'un nouveau /2, /3, /4, /3.S ou /3.PERM est créé par un autre appareil :
- l'application vérifie les nouvelles entrées environ toutes les 10 secondes lorsqu'elle est ouverte ;
- en arrière-plan, WorkManager effectue une vérification périodique avec connexion réseau et affiche une notification Android.

Le téléphone créateur ne se notifie pas lui-même.

## Sécurité

- Row Level Security est activé sur les tables du registre.
- Les applications clientes peuvent lire les entrées du registre.
- Elles ne peuvent pas modifier directement les compteurs.
- La création numérotée passe par la fonction PostgreSQL `registre_create_entry`.
- L'application utilise uniquement la **publishable key** Supabase, prévue pour être incluse dans une application mobile.
- Aucune clé `service_role` ou clé secrète n'est incluse dans l'APK.

## Stack

- Kotlin
- Jetpack Compose + Material 3
- Supabase / PostgreSQL
- PostgREST / RPC Supabase
- WorkManager
- GitHub Actions

## Construction

JDK 17 et Android SDK requis.

```bash
gradle :app:assembleDebug
```

L'APK est généré dans :

`app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions exécute les tests unitaires puis construit automatiquement l'APK.
