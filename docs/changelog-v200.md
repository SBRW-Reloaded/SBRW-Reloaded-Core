# SBRW Reloaded Core — Changelog & Documentation v2.0.0

## Migration

Run the SQL script **before** deploying the new version:

```
migrations/v103-v200/15-add-autotune-cache-and-parameters.sql
migrations/v103-v200/16-add-perf-locked-to-car-classes.sql
```

These scripts add:
- The `AUTO_TUNE_CACHE` table
- The `raceAgainEnabled` and `raceAgainMode` columns on `USER`
- All new entries in `PARAMETER`
- The `perf_locked` column on `CAR_CLASSES`

> The script is idempotent (`IF NOT EXISTS`, `INSERT IGNORE`).

---

## New Features

### 1. Auto-Tune (`/tune`)

Smart system that automatically proposes the best combination of performance parts to reach a target class.

#### Activation

| Parameter | Type | Default | Description |
|---|---|---|---|
| `SBRWR_ENABLE_AUTOTUNE` | bool | `false` | Enable/disable the `/tune` command |
| `SBRWR_AUTOTUNE_COMMAND_MAX_ITERATIONS` | int | `1000000` | DFS iteration limit when a player uses `/tune` (no cache). Lower = faster but less optimal |
| `SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS` | int | `10000000` | DFS iteration limit for pre-generation via ReloadAutoTune. Higher = more accurate results |

#### Chat Commands

| Command | Description |
|---|---|
| `/tune <Class>` | Propose a BALANCED tune for the target class (e.g. `/tune A`) |
| `/tune <Class> <priority>` | Propose a tune with a stat priority |
| `/tune confirm` | Apply the proposed tune (deducts cash/boost) |
| `/tune cancel` | Cancel the pending proposal |

**Available priorities:**

| Code | Priority |
|---|---|
| `t` | Top Speed |
| `a` | Acceleration |
| `h` | Handling |
| `th` | Top Speed + Handling |
| `ah` | Acceleration + Handling |
| *(none)* | Balanced |

**Example:** `/tune B ah` → proposes a class B tune focused on acceleration + handling.

#### How it works

1. The player types `/tune A t`
2. The system looks up the memory cache, then the DB cache, then computes in real-time
3. A summary is sent: current rating → target rating, number of parts, net cost (cash/boost), taxes
4. The player confirms with `/tune confirm` or cancels with `/tune cancel`
5. Parts are replaced, cash/boost is deducted (5% tax on resale gains)

#### Admin API: Cache Pre-generation

```
GET /Engine.svc/ReloadAutoTune?adminAuth=<TOKEN>
```

Pre-generates **all** setups for all cars × all classes × all priorities. Subsequent `/tune` results will be instant.

```
GET /Engine.svc/ReloadAutoTune?adminAuth=<TOKEN>&carName=<NAME>
```

Regenerates the cache for a specific vehicle only (deletes the old cache for that car then recomputes).

> `carName` matches the `full_name` field in the `CAR_CLASSES` table.

#### Performance Lock (`perf_locked`)

Individual cars can be locked from performance modifications via the `perf_locked` column in `CAR_CLASSES`.

When `perf_locked = 1`:
- The `/tune` command is blocked and the player receives a message: *"Performance modifications are locked for this car."*
- The car is excluded from cache pre-generation (`ReloadAutoTune`) and per-car regeneration

```sql
-- Lock a car
UPDATE CAR_CLASSES SET perf_locked = 1 WHERE store_name = 'car_store_name';
-- Unlock a car
UPDATE CAR_CLASSES SET perf_locked = 0 WHERE store_name = 'car_store_name';
```

---

### 2. Lobby Ready (`/ready`)

Allows players to vote to start the countdown immediately (reduced to 5 seconds).

#### Activation

| Parameter | Type | Default | Description |
|---|---|---|---|
| `SBRWR_ENABLE_LOBBY_READY` | bool | `false` | Enable/disable the `/ready` command |
| `SBRWR_READY_THRESHOLD` | int | `0` | Vote percentage required to trigger the start (0-100). E.g. `75` = 75% of players must vote |
| `SBRWR_READY_ENABLE_VOTEMESSAGES` | bool | `false` | Show other players when someone votes ready |

#### Chat Commands

| Command | Description |
|---|---|
| `/ready` or `/r` | Vote to start the lobby |

#### Conditions

- The player must be in a lobby (not in a race, not in freeroam)
- The lobby must have at least 2 players
- Each player can only vote once per lobby

---

### 3. Persistent RaceNow & Race Again

Persistent matchmaking queue: players who don't find a lobby are queued and automatically invited when a compatible lobby appears.

#### Activation

| Parameter | Type | Default | Description |
|---|---|---|---|
| `SBRWR_RACENOW_PERSISTENT_ENABLED` | bool | `false` | Enable the persistent RaceNow queue |
| `SBRWR_RACE_AGAIN_MIN_TIME` | int | `20000` | Minimum time (ms) before Race Again is offered |
| `RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD` | int | `30000` | Grace period (ms) before deleting an empty Race Again lobby |
| `SBRWR_RACENOW_AUTO_CREATE_DELAY` | int | `0` | Delay (ms) before auto-creating a lobby for queued players |
| `SBRWR_RACENOW_MAX_WAIT_MINUTES` | int | `5` | Max time (min) a player stays in queue before being removed |
| `SBRWR_RACENOW_MONITOR_INTERVAL` | int | `10000` | Queue scan interval (ms) |

#### New USER Columns

| Column | Type | Default | Description |
|---|---|---|---|
| `raceAgainEnabled` | boolean | `true` | Whether the player wants to use Race Again |
| `raceAgainMode` | int | `0` | `0` = RANDOM (random event), `1` = REPEAT (same event) |

#### New CAR_CLASSES Columns

| Column | Type | Default | Description |
|---|---|---|---|
| `perf_locked` | tinyint(1) | `0` | `0` = performance modifications allowed, `1` = autotune blocked for this car |

---

### 4. Lobby Car Class Lock

| Parameter | Type | Default | Description |
|---|---|---|---|
| `SBRWR_LOCK_LOBBY_CAR_CLASS` | bool | `false` | Lock the lobby to the creator's car class |

---

## Removed Files (vs. old version)

| File | Reason |
|---|---|
| `RankingBO.java` | Ranked system removed |
| `RankedDAO.java` | Associated DAO removed |
| `RankedEntity.java` | `RANKED` table no longer used by the core |
| `RaceAgainBO.java` | Logic replaced/integrated into `LobbyBO` + `LobbyCountdownBO` + `MatchmakingBO` |

> The columns `rankedMode`, `rankMin`, `rankMax` on `EVENT` and `rankingPoints` on `PERSONA` are no longer read by the core. They can remain in the database without impact.

---

## New Files

| File | Description |
|---|---|
| `AutoTuneBO.java` | Full business logic for the auto-tune system (DFS, cache, proposals) |
| `AutoTuneCommand.java` | Chat command handler for `/tune` |
| `AutoTuneCacheDAO.java` | DAO for the `AUTO_TUNE_CACHE` table |
| `AutoTuneCacheEntity.java` | JPA entity for `AUTO_TUNE_CACHE` |
| `AsyncXmppBO.java` | Asynchronous XMPP message sending (prevents transaction timeouts) |
| `ReadyCommand.java` | Chat command handler for `/ready` |
| `ReloadAutoTune.java` | Admin API endpoint `GET /ReloadAutoTune` |
| `RacerStatusConverter.java` | JPA attribute converter for the `RacerStatus` enum |

---

## Added Parameters Summary

| Parameter | Type | Default | Category |
|---|---|---|---|
| `SBRWR_ENABLE_AUTOTUNE` | bool | `false` | AutoTune |
| `SBRWR_AUTOTUNE_COMMAND_MAX_ITERATIONS` | int | `1000000` | AutoTune |
| `SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS` | int | `10000000` | AutoTune |
| `SBRWR_ENABLE_LOBBY_READY` | bool | `false` | Lobby Ready |
| `SBRWR_READY_THRESHOLD` | int | `0` | Lobby Ready |
| `SBRWR_READY_ENABLE_VOTEMESSAGES` | bool | `false` | Lobby Ready |
| `SBRWR_RACENOW_PERSISTENT_ENABLED` | bool | `false` | RaceNow |
| `SBRWR_RACE_AGAIN_MIN_TIME` | int | `20000` | Race Again |
| `RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD` | int | `30000` | Race Again |
| `SBRWR_RACENOW_AUTO_CREATE_DELAY` | int | `0` | RaceNow |
| `SBRWR_RACENOW_MAX_WAIT_MINUTES` | int | `5` | RaceNow |
| `SBRWR_RACENOW_MONITOR_INTERVAL` | int | `10000` | RaceNow |
| `SBRWR_LOCK_LOBBY_CAR_CLASS` | bool | `false` | Lobby |

---

## Translation Keys Reference

All messages sent to players via XMPP chat. Placeholders use the format `{0:s}`, `{1:s}`, etc. HTML `<font>` tags are used for in-game color formatting.

### Auto-Tune (`/tune`)

| Key | Placeholders | English Text |
|-----|-------------|--------------|
| `SBRWR_TUNE_USAGE` | `{0:s}` = available class names | `/tune <class> [h\|a\|t\|th\|ah] \| confirm \| cancel \| Classes: {0:s}` |
| `SBRWR_TUNE_INVALID_PRIORITY` | `{0:s}` = invalid priority entered | `Invalid '{0:s}'. Use h, a, t, th, or ah.` |
| `SBRWR_TUNE_ERROR` | — | `Tune error. Try again.` |
| `SBRWR_TUNE_NOCAR` | — | `No active car.` |
| `SBRWR_TUNE_NOPHYSICS` | — | `Can't identify car profile.` |
| `SBRWR_TUNE_PERFLOCKED` | — | `Performance modifications are locked for this car.` |
| `SBRWR_TUNE_UNKNOWNCLASS` | `{0:s}` = class entered, `{1:s}` = available classes | `Unknown class '{0:s}'. Available: {1:s}` |
| `SBRWR_TUNE_STOCKEXCEEDS` | `{0:s}` = stock rating, `{1:s}` = class name, `{2:s}` = class max | `Stock ({0:s}) exceeds class {1:s} max ({2:s}).` |
| `SBRWR_TUNE_CALCULATING` | — | `Calculating optimal parts...` |
| `SBRWR_TUNE_NOPARTS` | — | `No parts available.` |
| `SBRWR_TUNE_CANNOTREACH` | `{0:s}` = class name, `{1:s}` = best rating, `{2:s}` = min required | `Can't reach class {0:s}. Best: {1:s} / Need: {2:s}` |
| `SBRWR_TUNE_NOCOMBINATION` | `{0:s}` = class name | `No combination for class {0:s}.` |
| `SBRWR_TUNE_NEED_CASH` | `{0:s}` = cash needed, `{1:s}` = cash owned | `Need ${0:s}, have ${1:s}.` |
| `SBRWR_TUNE_NEED_BOOST` | `{0:s}` = boost needed, `{1:s}` = boost owned | `Need {0:s} boost, have {1:s}.` |
| `SBRWR_TUNE_PROPOSAL_BALANCED` | `{0:s}` = current rating, `{1:s}` = target rating, `{2:s}` = class, `{3:s}` = part count | `{0:s} -> {1:s} ({2:s}) \| Balanced \| {3:s} parts` |
| `SBRWR_TUNE_PROPOSAL_TOPSPEED` | *(same as above)* | `{0:s} -> {1:s} ({2:s}) \| Top Speed \| {3:s} parts` |
| `SBRWR_TUNE_PROPOSAL_ACCEL` | *(same as above)* | `{0:s} -> {1:s} ({2:s}) \| Accel \| {3:s} parts` |
| `SBRWR_TUNE_PROPOSAL_HANDLING` | *(same as above)* | `{0:s} -> {1:s} ({2:s}) \| Handling \| {3:s} parts` |
| `SBRWR_TUNE_PROPOSAL_TOPSPEED_HANDLING` | *(same as above)* | `{0:s} -> {1:s} ({2:s}) \| Top Speed & Handling \| {3:s} parts` |
| `SBRWR_TUNE_PROPOSAL_ACCEL_HANDLING` | *(same as above)* | `{0:s} -> {1:s} ({2:s}) \| Accel & Handling \| {3:s} parts` |
| `SBRWR_TUNE_CONFIRM` | `{0:s}` = cost summary, `{1:s}` = tax | `{0:s} (Tax: {1:s}) \| /tune confirm \| /tune cancel` |
| `SBRWR_TUNE_APPLIED` | `{0:s}` = final rating, `{1:s}` = class, `{2:s}` = part count, `{3:s}` = cost summary, `{4:s}` = tax | `Tune applied! {0:s} ({1:s}) \| {2:s} parts \| {3:s} (Tax: {4:s})` |
| `SBRWR_TUNE_SAFEHOUSE` | — | `Go to Safehouse to refresh parts.` |
| `SBRWR_TUNE_NOPENDING` | — | `No pending tune. Use /tune <class>.` |
| `SBRWR_TUNE_NOPENDING_CANCEL` | — | `Nothing to cancel.` |
| `SBRWR_TUNE_CANCELLED` | — | `Tune cancelled.` |
| `SBRWR_TUNE_CARCHANGED` | — | `Car changed. Please redo /tune.` |
| `SBRWR_TUNE_NOTFREEROAM` | — | `This command can only be used in freeroam.` |

### Lobby Ready (`/ready`)

| Key | Placeholders | English Text |
|-----|-------------|--------------|
| `SBRWR_READY_VOTEREGISTERED` | `{0:s}` = votes count, `{1:s}` = total players | `Vote saved. {0:s}/{1:s} players are ready.` |
| `SBRWR_READY_USERVOTED` | `{0:s}` = player name, `{1:s}` = votes count, `{2:s}` = total players | `{0:s} is ready! ({1:s}/{2:s} players)` |
| `SBRWR_READY_SUCCESS` | `{0:s}` = votes count, `{1:s}` = total players | `Ready check passed! ({0:s}/{1:s}) Starting in 5 seconds...` |
| `SBRWR_READY_WARNING_ALONE` | — | `You cannot use the ready command while being alone in a lobby.` |
| `SBRWR_READY_WARNING_ALREADYVOTED` | — | `You have already voted ready!` |
| `SBRWR_READY_WARNING_TOOLATE` | — | `Too late! The lobby is already starting.` |
| `SBRWR_READY_WARNING_ONEVENT` | — | `You cannot use the ready command during an event.` |
| `SBRWR_READY_WARNING_ONFREEROAM` | — | `You must be in a lobby to use the ready command.` |
| `SBRWR_READY_WARNING_DISABLED` | — | *(missing — feature disabled message)* |

### No Powerups (`/nopu`)

| Key | Placeholders | English Text |
|-----|-------------|--------------|
| `SBRWR_NOPU_JOIN_MSG` | `{0:s}` = vote % required | `You can now vote to disable powerups by entering /nopu in chat. Powerups will only be disabled once {0:s}% or more of the lobby has voted.` |
| `SBRWR_NOPU_USERVOTED` | `{0:s}` = player name, `{1:s}` = votes count, `{2:s}` = total players | `{0:s} voted to disable powerups. There is {1:s} out of {2:s} votes.` |
| `SBRWR_NOPU_INFO_SUCCESS` | — | `Powerups will be disabled in the upcoming race.` |
| `SBRWR_NOPU_INFO_NOTENOUGHVOTES` | — | `The number of votes to disable powerups is insufficient, powerups remains enabled.` |
| `SBRWR_NOPU_MODE_ENABLED` | — | `Powerups are disabled for the current lobby.` |
| `SBRWR_DISABLEDPOWERUP` | — | `This powerup cannot be used in multiplayer.` |
| `SBRWR_NOPU_WARNING_VOTEENDED` | — | `The vote for no-powerup has ended.` |
| `SBRWR_NOPU_WARNING_ALREADYVOTED` | — | `You already voted to enable no-powerup feature.` |
| `SBRWR_NOPU_WARNING_ALONE` | — | `You cannot use this command while being alone in a lobby.` |
| `SBRWR_NOPU_WARNING_ONEVENT` | — | `This command cannot be used during an event.` |
| `SBRWR_NOPU_WARNING_ONFREEROAM` | — | `This command cannot be used in freeroam.` |

### Livery (`/livery`)

| Key | Placeholders | English Text |
|-----|-------------|--------------|
| `SBRWR_LIVERY_NOOPTION` | — | `No option provided, try using import or export.` |
| `SBRWR_LIVERY_IMPORT_UNKNOWN` | — | `Please provide a livery code.` |
| `SBRWR_LIVERY_IMPORT_NONEXISTENT` | — | `This livery code doesn't exist.` |
| `SBRWR_LIVERY_IMPORT_BANNED` | — | *(missing — livery banned message)* |
| `SBRWR_LIVERY_IMPORT_SUCCESS` | — | `The livery has been imported, please enter safehouse to check the result.` |
| `SBRWR_LIVERY_IMPORT_NOTCOMPATIBLE` | `{0:s}` = livery code | ``Sorry, but this livery is not compatible with your current car in use, use `/livery import {0:s} --force` to force the import.`` |
| `SBRWR_LIVERY_EXPORT_SUCCESS` | `{0:s}` = share code | `Your livery has been exported, the share code is: {0:s}` |
| `SBRWR_LIVERY_EXPORT_NONEXISTENT` | — | `In order to export a livery you need vinyls on your current car.` |

### Matchmaking

| Key | Placeholders | English Text |
|-----|-------------|--------------|
| `SBRWR_MATCHMAKING_IGNOREDEVENT` | `{0:s}` = event name | `You declined the event {0:s}. It won't show again until you finish an event or go to the safehouse.` |
