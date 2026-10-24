# appliance-service

Watches the washing machine, the dryer and the dishwasher through the power draw of their Shelly
plugs (epic HAS-270): tells when a cycle runs and when it has finished, tells the household, reminds
it until somebody acknowledges it in the application, and keeps the history of the cycles.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: `database-service` (the device registry - the kind `appliance` and its
  `ApplianceSettings`, `internal.service.database`), the Shelly plugs over HTTP (from HAS-278),
  RabbitMQ (publishes the household notifications); routed by `api-gateway-service` from HAS-281.
- Uses libraries: `cholewa-commons` 1.12.0 (`NotificationPublisher.publishHousehold`, the pooled
  `ConnectionFactory`, `ScheduledPass`, the error contract), `smart-home-sdk` 1.13.0
  (`SmartDevice`, `ApplianceSettings`, `HouseholdNotification`), `shelly-client` 2.1.0
  (`ShellyGen1Client` - the plugs are Gen1).
- Database: own database `home-automation-appliance`, pool 2 - with it 18 of the 22 backend
  connections are allotted; a rollout of one service reaches 21. **The last service with a
  database before the shares are re-cut** (HAS-277).
- Integrates with hardware: Shelly plugs (Gen1, polled - no `/device` consumer: Gen1 does not
  report over MQTT).

## Build & run

- Build + tests: `mvn verify` - no Docker needed until the first test of PostgreSQL-only SQL
  (Testcontainers, the dependencies are in the pom already).
- Local run: `home,local` Spring profiles, port `6013` (Actuator `8013`); in-cluster port
  `6200`, Actuator `8200`. Needs `rabbitmq-password` (the user `notification`), PostgreSQL
  (`database.*`) and, for the registry, database-service on 6005. **The real `database-*` values
  are the production database** - a migration is rehearsed on a throwaway PostgreSQL, never by a
  local run.
- Spring Boot 4.1.2, logbook 4.2.0; own libraries on the latest releases, as the org rule asks of
  every service task.

## Specifics

- **One instance, never scaled**: the state of the cycles lives in memory between the polls
  (HAS-278), so the Deployment is `Recreate`.
- **One broker connection, to `/notification`**: the publisher of `cholewa-commons` over the
  connection of the application (`cholewa.notification.enabled`). It needs correlated confirms,
  returns and the mandatory flag on the template and refuses to start without them (pinned by
  `ApplianceServiceApplicationTest`). `env` is `prod` in `home`, `dev` in `local` and `test` - a
  run outside the cluster never publishes to the household; `home` alone points at
  `rabbitmq-headless`, which resolves only in the cluster (the same shape as weather, blinds and light). `rabbitmq-password` has no default:
  `cholewa.required-properties` refuses the start.
- **Scheduling is off under `test`** (`SchedulingConfig`, `@Profile("!test")`), and the `test`
  document points the broker and database-service at port 1 - a context test neither polls nor
  publishes. Every reactive `@Scheduled` method of HAS-278 onwards is wrapped in
  `ScheduledPass.bounded`, its limit below its delay; the scheduler has the pool of 10 of the
  library.
- **`shelly-client` keeps its beans** (`ShellyGen1Client` for the plugs) - unlike water-service and
  boiler-service, which switch them off.
- **The client of database-service** (`databaseServiceWebClient`) comes from the injected builder -
  traced and logged by Logbook - with a connect timeout and a response timeout of 5 s, and its own
  named `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits), pinned by
  `DatabaseServiceClientConfigTest`.
- **Flyway logs `No migrations found` (WARN) on every start** until HAS-278 adds `V1` - expected;
  the start is otherwise clean (the schema history table proves the grant on `public`).
- **Tests use `mockwebserver3`**, never the legacy `mockwebserver` (JUnit 4) of the reference.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
