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

**This is the scaffold (HAS-277).** The service starts, connects nothing it does not need yet and
serves the Actuator only. What comes next:

| Task | What |
|---|---|
| HAS-278 | power sampling of the plugs (Shelly Gen1, polled) and the cycle detection, the first table |
| HAS-279 | the notifications, the reminders and the acknowledgement |
| HAS-280 | the availability monitor of the plugs |
| HAS-281 | the routes of `api-gateway-service` |

The appliances are devices of the registry of `database-service` - the kind `appliance` with its
`ApplianceSettings` (thresholds of the draw, the times of a cycle, the reminders, the quiet time) -
read through `internal.service.database`.

# Run locally

- Build: `mvn verify` (JDK 21). No test needs Docker yet; the first test of PostgreSQL-only SQL
  (Testcontainers, the dependencies are in the pom) will - from then on `mvn verify` needs a
  running Docker.
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

None yet - the endpoints come with HAS-279, their routes with HAS-281.

# Database

`home-automation-appliance`, owned by `doadmin` like every database of the cluster: the user of
the services needs `GRANT USAGE, CREATE ON SCHEMA public` before the first start, or Flyway fails
with `permission denied for schema public`. No migration yet - the first table comes with HAS-278.
