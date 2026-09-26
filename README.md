# Nexora Analytics

Dashboard web d'analytics pour un réseau Minecraft **Velocity + Paper 1.21.5**, avec intégration des îles **NexoraMc**.

Le projet produit deux plugins :

| Jar | Où l'installer | Rôle |
|---|---|---|
| `NexoraAnalytics-Velocity-1.0.0.jar` | Proxy Velocity (3.4+) | Suit les connexions de tout le réseau, stocke les données (SQLite), héberge le dashboard web et la commande `/analytics`. |
| `NexoraAnalytics-Paper-1.0.0.jar` | Chaque serveur Paper | Collecte économie (Vault), îles NexoraMc, ressources, TPS/mémoire, erreurs console et crashs, puis les envoie au proxy. |

## Données affichées

- 👤 Joueurs uniques, 🆕 nouveaux joueurs du jour (comparés à la veille)
- 📈 Joueurs actifs aujourd'hui / 7 jours / 30 jours, joueurs actifs par jour
- 🔄 Rétention J1 / J7 / J30 + matrice de cohortes hebdomadaires (J1, J3, J7, J14, J30)
- ⏱️ Temps de jeu moyen par joueur et par jour, durée moyenne des sessions
- 🚪 Connexions / déconnexions (du jour et par jour)
- 🕐 Heures d'affluence + carte de chaleur jour × heure, courbe des joueurs en ligne sur 24 h par serveur
- 🌍 Première et dernière connexion de chaque joueur (liste avec recherche, tri et fiche détaillée)
- 🔁 Joueurs revenus après ≥ 1, 7 ou 30 jours d'absence
- 💰 Argent généré / dépensé par jour et par serveur, plus gros gains et dépenses
- 🏝️ Îles créées, ⛏️ chunks débloqués, 🏆 niveau moyen des îles et progression moyenne des joueurs, classement des îles
- 📦 Ressources : blocs minés, blocs posés, objets fabriqués (top matériaux)
- 🖥️ Versions de Minecraft et clients utilisés (Vanilla, Fabric, Lunar…)
- 🔌 Serveur actuel de chaque joueur en ligne, état de chaque serveur (TPS, MSPT, mémoire, chunks, entités)
- 📊 Pic de joueurs simultanés (du jour, record absolu, historique par jour)
- ⚠️ Crashs, erreurs console (avec stack trace) et coupures de serveur
- 🔥 Streaks de connexion (en cours, record, classement)
- 💤 Joueurs devenus inactifs, joueurs à risque, joueurs réguliers perdus

## Installation

1. **Compiler** (Java 21, Maven) :
   ```bash
   mvn clean package
   ```
   Les jars sont dans `velocity/target/` et `paper/target/`. Chaque module est autonome : vous pouvez aussi lancer `mvn clean package` directement dans `velocity/` ou dans `paper/`.

2. **Proxy Velocity** : placez `NexoraAnalytics-Velocity-1.0.0.jar` (≈ 14 Mo, **pas** le fichier `original-…`) dans `plugins/`, démarrez le proxy une fois, puis éditez `plugins/nexora-analytics/config.properties` :
   - `public-url` : l'adresse à laquelle **vous** ouvrirez le dashboard dans votre navigateur (ex. `http://123.45.67.89:8765`) ;
   - `port` : le port du dashboard (à ouvrir dans le pare-feu si vous y accédez depuis l'extérieur) ;
   - `ingest-secret` est généré automatiquement : vous en aurez besoin à l'étape suivante (`analytics secret` dans la console du proxy l'affiche aussi).

3. **Chaque serveur Paper** : placez `NexoraAnalytics-Paper-1.0.0.jar` dans `plugins/`, démarrez une fois, puis éditez `plugins/NexoraAnalytics/config.yml` :
   - `server-name` : le nom du serveur **exactement** comme dans `velocity.toml` (`lobby`, `skyblock`…) ;
   - `proxy-url` : l'adresse du proxy vue depuis ce serveur (souvent `http://127.0.0.1:8765`) ;
   - `secret` : la valeur `ingest-secret` du proxy.

4. **Permission** : donnez `nexora.analytics.admin` aux admins **sur le proxy** (ex. LuckPerms Velocity : `/lpv user <pseudo> permission set nexora.analytics.admin true`).

## Accès au dashboard

En jeu, tapez **`/analytics`** : un lien cliquable, personnel, **valable 5 minutes et utilisable une seule fois**, s'affiche dans le chat. Il ouvre une session de 12 heures dans le navigateur (cookie `HttpOnly`, `SameSite=Strict`). Sans ce lien, le dashboard n'affiche que l'écran de connexion et l'API répond `401`.

| Commande | Description |
|---|---|
| `/analytics` (alias `/nexoraanalytics`, `/nadash`) | Envoie le lien d'accès au dashboard. |
| `/analytics logout` | Ferme toutes les sessions ouvertes du dashboard. |
| `/analytics secret` | Affiche le secret d'ingestion (console du proxy uniquement). |

> Pour un accès depuis Internet, placez idéalement le dashboard derrière un reverse proxy HTTPS (Nginx, Caddy…) et indiquez l'URL `https://…` dans `public-url` : le cookie de session est alors marqué `Secure`.

## Fonctionnement et limites

- **Économie** : Vault ne publie aucun événement de transaction. Le solde des joueurs connectés est donc relevé toutes les 30 s : une hausse compte comme argent généré, une baisse comme argent dépensé. Un paiement entre deux joueurs apparaît donc des deux côtés, et les variations de solde d'un joueur hors ligne ne sont pas vues.
- **Îles NexoraMc** : NexoraMc n'émet pas d'événements. Le collecteur lit donc ses îles chaque minute (via `getIslands().all()` et `getLevels()`), et le proxy compare les instantanés successifs pour en déduire les créations d'îles et les chunks débloqués (surface de bordure / 256). Les îles existant avant l'installation servent de référence et ne sont pas comptées comme « créées ».
- **Rétention Jn** : part des joueurs arrivés il y a entre n et n + 29 jours qui se sont reconnectés exactement n jours après leur première connexion.
- **Crashs** : un fichier témoin détecte les arrêts non propres d'un serveur Paper (crash, kill, coupure) ; le proxy signale aussi tout serveur qui ne répond plus au ping.
- Les données sont conservées dans `plugins/nexora-analytics/analytics.db` (SQLite) ; les échantillons de joueurs en ligne sont gardés 120 jours et les incidents 60 jours (configurable).
- Le driver SQLite est embarqué dans le jar Velocity. S'il manque (jar `original-…` ou construit sans Maven), il est téléchargé automatiquement depuis Maven Central au premier démarrage dans `plugins/nexora-analytics/libs/` et vérifié par empreinte SHA-256.
