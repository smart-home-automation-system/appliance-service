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
(`smart-home-sdk` 1.13.0) published with the `NotificationPublisher` of `cholewa-commons` 1.12.0;
`notification-service` words it in Polish for the household's Discord channel, SMS and Web Push.

**Since HAS-278 it reads the plugs and detects the cycles.** The appliances are devices of the
registry of `database-service` - the kind `appliance` with its `ApplianceSettings` - read at the
start and every 5 minutes; a Gen1 plug is polled every 30 s (`GET /meter/<channel>`, the
`meter` of `shelly-client` 2.2.0). Each valid sample is stored and given to the detector of its
appliance:

| From | To | When |
|---|---|---|
| `idle` | `running` | the draw stays above `runningAboveWatts` for a minute - the cycle starts at the first sample above |
| `running` | `finishing` | a sample below `idleBelowWatts` |
| `finishing` | `running` | a sample at or above `idleBelowWatts` - a pause of a washing machine |
| `finishing` | `finished` | `finishedAfterMinutes` below `idleBelowWatts`, when the cycle lasted at least `minRunMinutes`; a shorter one is discarded, back to `idle`, nobody is told |
| `finished` | `idle` | acknowledged (HAS-279) or claimed by a tag (HAS-343) |
| `finished` | `running` | a new cycle starts |

An invalid sample (`is_valid: false`) or a plug that does not answer keeps the state.

**Since HAS-279 the household is told.** A finished cycle is announced within a minute of its end -
quiet hours or not, with the time of the end - as `appliance finished` of the `household` category (the JSON `HouseholdNotification`, the name of
the appliance from the registry as its subject, `acknowledgePath` `/room`); `notification-service`
words it in Polish for Discord, SMS and Web Push. Then a reminder every `reminderEveryMinutes` after
the last message, at most `maxReminders`, never between `quietFrom` and `quietTo` (a reminder due
then waits for their end), until the cycle is acknowledged - `POST /home/appliance/{id}/acknowledge`,
which publishes `appliance acknowledged`. A new cycle on an unacknowledged appliance closes the old
one as acknowledged, without a message. What was sent is in the row of the cycle, so a restart sends
nothing twice, and a message that did not get through is sent again a minute later. What comes next:

| Task | What |
|---|---|
| HAS-280 | the availability monitor of the plugs |
| HAS-281 | the routes of `api-gateway-service` |

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

# API

Under the base path `/home/appliance`; not routed by the gateway before HAS-281. The
times are the clock of the house without an offset; a field not known is left out.

| Method | Path | Answer |
|---|---|---|
| `GET` | `/home/appliance` | every watched appliance: `id`, `name`, `room`, `state` (`idle`, `running`, `finishing`, `finished`), `watts` and `sampledAt` (the last valid reading), `runningSince`, `finishedAt` (the fall of the draw), `awaitingSince` (the moment the end was certain), `reachable` (the last poll answered) |
| `GET` | `/home/appliance/{id}` | one of them; `404 NOT_FOUND_APPLIANCE` |
| `GET` | `/home/appliance/{id}/runs?from=&to=` | the cycles that started in the range (at most 31 days): `id`, `startedAt`, `finishedAt`, `energyWh`, `maxWatts`, `acknowledgedAt` |
| `GET` | `/home/appliance/{id}/samples?from=&to=` | the samples of the range (at most 2 days): `at`, `watts` |
| `POST` | `/home/appliance/{id}/acknowledge` | `204` - the finished cycle acknowledged, its reminders stop (a mute for the administrator once the tag claims of HAS-343 acknowledge for the members); `409 NOTHING_TO_ACKNOWLEDGE` when no finished cycle waits, `404 NOT_FOUND_APPLIANCE` |

`from` and `to` are local date-times (`2026-10-24T00:00:00`); an offset, `to` not after `from` or a
longer range is a `400`. The history is answered for a watched appliance only: one
switched off or removed in the registry is a `404`, though its rows stay.

# Database

`home-automation-appliance`, owned by `doadmin` like every database of the cluster: the user of
the services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails
with `permission denied for schema public`.

- `appliance_run` (V1) - one row per cycle, inserted when it starts and completed when it has
  finished; a run below `minRunMinutes` is deleted. V3 adds its messages: `notified_at` (the finish
  announced), `last_message_at`, with `reminders_sent` and `acknowledged_at` of V1.
- `appliance_sample` (V2) - every valid sample, kept `appliance.sample-retention` (14 days, a bare
  number is days, 2-90) and deleted every night at 03:20.

Times are `timestamptz`.
