# Registre Communautaire

Application Android pour partager la numérotation des cahiers administratifs et éviter les doublons entre utilisateurs.

## Cahiers gérés

- /2
- /3
- /4
- /3.S
- /3.PERM

Chaque nouvelle année utilise une séquence indépendante qui repart automatiquement à 1. Les années précédentes restent consultables et sont considérées comme clôturées dans l'application.

## Principe de numérotation communautaire

La numérotation officielle est réservée dans Cloud Firestore au moyen d'une transaction atomique sur un compteur `année + type de cahier`. Deux téléphones ne peuvent donc pas valider le même numéro.

En mode hors ligne, une saisie peut être enregistrée comme **brouillon en attente de numéro**. Dès que le réseau revient, un travail de synchronisation réserve le prochain numéro disponible. L'application n'invente jamais un numéro définitif hors ligne.

## Rappels déplacement / permission

Pour un déplacement ou une permission, l'utilisateur indique le nombre de jours. Le jour du départ compte comme **jour 1** :

`date_arrivée = date_départ + (nombre_de_jours - 1)`

Exemple : départ aujourd'hui pour 2 jours = aujourd'hui + demain, arrivée demain.

Un rappel local Android est programmé le matin de la date d'arrivée pour préparer le message de disponibilité. Le rappel repose sur WorkManager et reste planifié après fermeture de l'application et redémarrage du téléphone.

## Stack

- Kotlin
- Jetpack Compose + Material 3
- Firebase Authentication anonyme
- Cloud Firestore
- WorkManager
- GitHub Actions pour construire l'APK

## Firebase utilisé

Projet Firebase prévu pour cette application :

- Project ID : `archive-9631f`
- Package Android de l'application : `mg.registre.communautaire`

La configuration reçue initialement correspond à une **application Web Firebase**. Son App ID contient `:web:` et ne doit pas être utilisé comme App ID Android.

### Enregistrer l'application Android dans Firebase

Dans Firebase Console :

1. Ouvrir le projet `archive-9631f`.
2. Aller dans **Paramètres du projet > Vos applications**.
3. Ajouter une application **Android**.
4. Saisir exactement le package :
   `mg.registre.communautaire`
5. Enregistrer l'application.
6. Copier l'**App ID Android**, au format :
   `1:...:android:...`

Le projet peut fonctionner avec l'initialisation programmatique déjà présente ; il faut fournir :

```
FIREBASE_API_KEY=...
FIREBASE_APPLICATION_ID=1:...:android:...
FIREBASE_PROJECT_ID=archive-9631f
```

Puis activer dans Firebase :

1. **Authentication > Sign-in method > Anonymous**
2. **Firestore Database**
3. Déployer les règles présentes dans `firestore.rules`

### GitHub Actions

Le workflow lit les variables suivantes :

- `FIREBASE_API_KEY`
- `FIREBASE_APPLICATION_ID`
- `FIREBASE_PROJECT_ID`

Une fois ces trois valeurs définies pour GitHub Actions, chaque push sur `main` teste et construit automatiquement l'APK connecté à Firebase.

## Construction

JDK 17 et Android SDK requis.

```bash
gradle :app:assembleDebug
```

L'APK est généré dans `app/build/outputs/apk/debug/`.

## Sécurité fonctionnelle

- Les anciens exercices sont en lecture seule dans l'interface.
- Les compteurs sont séparés par année et par cahier.
- Les entrées sans connexion restent `PENDING_NUMBER`.
- Une transaction Firestore attribue le numéro final et met à jour le compteur dans la même opération.
