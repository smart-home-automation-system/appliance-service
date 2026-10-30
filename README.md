# appliance-service

[![CI](https://github.com/smart-home-automation-system/appliance-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/appliance-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_appliance-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_appliance-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_appliance-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_appliance-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/appliance-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/appliance-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/appliance-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_appliance-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_appliance-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_appliance-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_appliance-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/appliance-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/appliance-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/appliance-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/appliance-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/appliance-service?style=plastic)

---


# Description

Watches the washing machine, the dryer and the dishwasher through the power draw of the Shelly
plugs they run on (epic HAS-270). From the draw it tells when a cycle runs and when it has
finished, tells the household that the appliance needs attention, reminds it until somebody
acknowledges it in the application, and keeps the history of the cycles. The household is told
through the `household` category of `notification-service` - a JSON `HouseholdNotification`
(`smart-home-sdk` 1.15.0) published with the `NotificationPublisher` of `cholewa-commons` 1.12.0;
`notification-service` words it in Polish for the household's Discord channel, SMS and Web Push.

**Since HAS-278 it reads the plugs and detects the cycles.** The appliances are devices of the
registry of `database-service` - the kind `appliance` with its `ApplianceSettings` - read at the
start and every 5 minutes; a Gen1 plug is polled every 30 s (`GET /meter/<channel>`, the
`meter` of `shelly-client` 2.3.0). Each valid sample is stored and given to the detector of its
appliance:

| From | To | When |
|---|---|---|
| `idle` | `running` | the draw stays above `runningAboveWatts` for a minute - the cycle starts at the first sample above |
| `running` | `finishing` | a sample below `idleBelowWatts` |
| `finishing` | `running` | the draw stays at or above `idleBelowWatts` for a minute - a washing machine going on after a pause; a shorter draw (the drum of a dryer turning now and then while it waits to be emptied, HAS-285) is ignored and the end still counts from the fall |
| `finishing` | `finished` | `finishedAfterMinutes` below `idleBelowWatts`, when the cycle lasted at least `minRunMinutes`; a shorter one is discarded, back to `idle`, nobody is told |
| `finished` | `idle` | acknowledged (HAS-279) or claimed by a tag (HAS-343) |
| `finishing` | `idle` | acknowledged before the end was certain (HAS-348): the cycle ends at the fall of the draw, never becomes `finished` and is never announced or reminded - the household gets `appliance acknowledged` only |
| `finished` | `running` | a new cycle starts |

An invalid sample (`is_valid: false`) or a plug that does not answer keeps the state.

**Since HAS-279 the household is told.** A finished cycle is announced within a minute of its end -
quiet hours or not, with the time of the end - as `appliance finished` of the `household` category (the JSON `HouseholdNotification`, the name of
the appliance from the registry as its subject, `acknowledgePath` `/room`); `notification-service`
words it in Polish for Discord, SMS and Web Push. Then a reminder every `reminderEveryMinutes` after
the last message, at most `maxReminders`, never between `quietFrom` and `quietTo` (a reminder due
then waits for their end), until the cycle is acknowledged - `POST /home/appliance/{id}/acknowledge`,
which publishes `appliance acknowledged` - with the `member` who acknowledged it, when the application named one (HAS-347). A new cycle on an unacknowledged appliance closes the old
one as acknowledged, without a message. What was sent is in the row of the cycle, so a restart sends
nothing twice, and a message that did not get through is sent again a minute later.

**Since HAS-280 the plugs are watched.** A plug that gives no reading - no answer, or an answer without one
(`is_valid: false`) - for `appliance.monitor.offline-after` (`PT5M`) hides the end of a cycle, so it is
announced on the technical channel of `notification-service` (`alert`, English, the alerts channel - never the
household's): one alert per check naming every plug out, a reminder every `appliance.monitor.reminder-every`
(`PT1H`), one info when it reads again. Checked every minute from the second; a bare number of either setting is
minutes. The alerts given live in memory: after a restart an outage still going on is announced again, and the
return of one announced before it is not.

**Since HAS-343 a chore is claimed with the NFC tag on the appliance.** Each appliance carries an NTAG 424 DNA
with SUN in its encrypted form: every read of the tag opens a URL with its UID and its read counter encrypted
(`picc_data`) and an AES-CMAC over both (the SDM chapter of NXP AN12196 -
the session key from `3CC300010080 || UID || counter`, the MAC over nothing else, its
odd bytes sent), so a link copied or bookmarked is worth nothing. The tag page of the dashboard (HAS-346) posts
what it read, with the member of its profile, to `POST /home/appliance/claims`. The service checks the tag
(`appliance.nfc.tags` - the UID of each tag and the id of its appliance - and the key `appliance.nfc.key`), the
member (the household profiles) and the counter (above the last one taken, in one statement); then a finished
cycle waiting is acknowledged as from the application - the reminders stop, `appliance acknowledged` names the
member - and the claim is stored against the latest cycle that finished within `appliance.claim-window` (12 h,
a bare number is hours), acknowledged before or not. What comes next:

| Task | What |
|---|---|
| HAS-344 | the claim with the snapshot of who was at home |
| HAS-345 | the report of the chores per member |

# Run locally

- Build: `mvn verify` (JDK 21) - **needs a running Docker**: `ApplianceRepositoriesTest` runs the
  migrations on a PostgreSQL 17 in Testcontainers.
- Ports: local profile `6013` (management `8013`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The ingress routes
  only 6200, so Actuator is reachable inside the cluster only - where the Kubernetes probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- **The broker**: one connection, to the virtual host `/notification` as the user `notification`
  (`rabbitmq-password` - mandatory, the service refuses to start without it). The publisher of
  `cholewa-commons` checks at the start that the template publishes with correlated confirms,
  returns and the mandatory flag. The `local` profile publishes with `env: dev` - into the dev
  queue, never to the household.
- **The database**: own database `home-automation-appliance`, `database.pool.max-size: 2` - this
  service's share of the 22 backend connections of the managed database (18 allotted with it).
  The connection properties (`database-host`, `database-port`, `database-name`, `database-user`,
  `database-password`) are bound to the `database.*` prefix of `cholewa-commons`; Flyway derives
  its JDBC URL from them (`--flyway-url=...` overrides). Flyway runs in `home` / `local` and is
  off under `test`. **A local run with the real values is the production database.**
- `database-service` is `internal.service.database` - `http://localhost:6005` in the `local`
  profile.
- **The key of the tags**: `appliance-nfc-key` (32 hex characters, AES-128) - mandatory, the service refuses to
  start without it. The tags: `appliance.nfc.tags.<uid>=<device id>` (in the manifest the variable
  `APPLIANCE_NFC_TAGS_<UID>`); without them every claim is `400 INVALID_TAG`.

# API

Under the base path `/home/appliance`, routed by the gateway (`/appliance`, `/appliance/**`, HAS-281). The
times are the clock of the house without an offset; a field not known is left out.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/appliance` | every watched appliance: `id`, `name`, `room`, `state` (`idle`, `running`, `finishing`, `finished`), `watts` and `sampledAt` (the last valid reading), `runningSince`, `finishedAt` (the fall of the draw), `awaitingSince` (the moment the end was certain), `reachable` (the last poll answered) |
| `GET` | `/home/appliance/{id}` | one of them; `404 NOT_FOUND_APPLIANCE` |
| `GET` | `/home/appliance/{id}/runs?from=&to=` | the cycles that started in the range (at most 31 days): `id`, `startedAt`, `finishedAt`, `energyWh`, `maxWatts`, `acknowledgedAt`, `acknowledgedBy` (who acknowledged it in the application, HAS-347) |
| `GET` | `/home/appliance/{id}/samples?from=&to=` | the samples of the range (at most 2 days): `at`, `watts` |
| `POST` | `/home/appliance/{id}/acknowledge?member=` | `204` - the finished cycle acknowledged, its reminders stop; a cycle still `finishing` (HAS-348) is ended at the fall and acknowledged at once, never announced (only `appliance acknowledged` goes out), one shorter than `minRunMinutes` too (a member saw it; unacknowledged it is still discarded unseen); `member` (optional, 3-50, HAS-347) - who acknowledges it, checked against the profiles of the household whatever the case and stored and told by the name the registry has, `400 UNKNOWN_MEMBER` for a name it does not have (nothing is acknowledged), and a failed read of the profiles acknowledges without a name (a mute for the administrator now that the tag claims of HAS-343 acknowledge for the members); `409 NOTHING_TO_ACKNOWLEDGE` when no finished or finishing cycle waits, `404 NOT_FOUND_APPLIANCE` |
| `POST` | `/home/appliance/claims` | a chore claimed with the tag of an appliance (HAS-343), body `{piccData, cmac, member}` - 32 and 16 hex characters of any case, the `picc_data` and `cmac` of the URL of the tag, the member 3-50: `200` with `applianceId`, `name`, `state` (after the claim), `member` (the name the registry has), `runId`, `claimedAt`; a finished or finishing cycle waiting is acknowledged with the member first. `400 INVALID_TAG` - `picc_data` and the MAC are not what the key of the tags gives, or the UID inside is not a tag of an appliance; `400 UNKNOWN_MEMBER`; `404 NOT_FOUND_APPLIANCE` - the appliance of the tag is not watched; `409 REPLAYED_TAG` - the counter is not above the last one taken; `409 NOTHING_TO_CLAIM` - no cycle finished within `appliance.claim-window` (the read is spent); `503` - the household profiles could not be read (the read is not spent, post it again); a body that is not a tag read is a `400` |

`from` and `to` are local date-times (`2026-10-24T00:00:00`); an offset, `to` not after `from` or a
longer range is a `400`. The history is answered for a watched appliance only: one
switched off or removed in the registry is a `404`, though its rows stay.

# Database

`home-automation-appliance`, owned by `doadmin` like every database of the cluster: the user of
the services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails
with `permission denied for schema public`.

- `appliance_run` (V1) - one row per cycle, inserted when it starts and completed when it has
  finished; a run below `minRunMinutes` is deleted. V3 adds its messages: `notified_at` (the finish
  announced), `last_message_at`, with `reminders_sent` and `acknowledged_at` of V1; V4 adds `acknowledged_by` (HAS-347).
- `appliance_tag` and `appliance_claim` (V5, HAS-343) - each tag read, with the counter of its latest accepted
  read; each claim with its cycle, the member, the source (`nfc`; `app` is reserved, nothing writes it), the time
  and the counter.
- `appliance_sample` (V2) - every valid sample, kept `appliance.sample-retention` (14 days, a bare
  number is days, 2-90) and deleted every night at 03:20.

Times are `timestamptz`.
