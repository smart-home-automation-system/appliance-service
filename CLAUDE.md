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
  `ApplianceSettings`, `internal.service.database`; the household profiles, HAS-347), the Shelly plugs over HTTP (`GET /meter/<channel>`, HAS-278),
  RabbitMQ (publishes the household notifications - `appliance finished`, `reminder`, `acknowledged`, HAS-279); routed by `api-gateway-service` from HAS-281.
- Uses libraries: `cholewa-commons` 1.12.0 (`NotificationPublisher.publishHousehold`, the pooled
  `ConnectionFactory`, `ScheduledPass`, the error contract), `smart-home-sdk` 1.14.0
  (`SmartDevice`, `ApplianceSettings`, `HouseholdNotification`), `shelly-client` 2.2.0
  (`ShellyGen1Client.meter` - the plugs are Gen1; added to the library for this service in HAS-278).
- Database: own database `home-automation-appliance`, pool 2 - with it this project holds 18 of the
  47 connections of the managed PostgreSQL (22 until the plan change of 2026-10-25; the split is in
  `organization.md`).
- Integrates with hardware: Shelly plugs (Gen1, polled - no `/device` consumer: Gen1 does not
  report over MQTT).

## Build & run

- Build + tests: `mvn verify` - **needs a running Docker** (`ApplianceRepositoriesTest` runs V1 and V2
  on a PostgreSQL 17 in Testcontainers).
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
  publishes. Every reactive `@Scheduled` method is wrapped in `ScheduledPass.bounded`, its limit below its
  delay (pinned by `MonitorScheduleTest`, `SampleRetentionTest`); the scheduler has the pool of 10 of
  the library.
- **`shelly-client` keeps its beans** (`ShellyGen1Client` for the plugs) - unlike water-service and
  boiler-service, which switch them off.
- **The client of database-service** (`databaseServiceWebClient`) comes from the injected builder -
  traced and logged by Logbook - with a connect timeout and a response timeout of 5 s, and its own
  named `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits), pinned by
  `DatabaseServiceClientConfigTest`.
- **The cycle detection (HAS-278)** - `MonitorSchedule` drives two passes of `ApplianceMonitor`:
  `refresh` (start, then every 5 min, bounded 4 min) reads the registry through `ApplianceRegistry`
  (a failed read keeps the appliances it has; a device it cannot watch - no binding, no settings, a
  Gen2+ plug - is a WARN once until it changes) and starts a new appliance from its latest run
  (`RunStore.restore`); `sample` (every 30 s from 15 s, bounded 25 s) reads every plug at once
  through `PowerReader` (`Gen1PowerReader`: `ShellyGen1Client.meter`, the time of the pod - the
  timestamp of the plug is its local time written as UTC), then one appliance after the other
  stores the valid sample and gives it to its `CycleDetector`; a transition goes to `RunStore`.
- **`CycleDetector` is pure** (no I/O, no clock - the time of the sample decides) and synchronized:
  the rules are in its comment and the README, the cases of the DoD in `CycleDetectorTest`. The
  start rule is the owner's of 2026-10-24: a minute above `runningAboveWatts` starts the cycle at
  its first sample, `minRunMinutes` is the least length of the whole cycle checked at its end - the
  description of `ApplianceSettings` in smart-home-sdk 1.13.0 says otherwise, to be corrected with
  its next release. **A pause ends only after a minute of draw** (HAS-285, 2026-10-26): the dryer of
  the house turns its drum for a few seconds about every minute while it waits to be emptied (75-210 W,
  one sample between samples of 2 W), and one sample at or above `idleBelowWatts` used to send the cycle
  back to `running` - it was never announced while the drum kept turning. Now `finishing` goes back to
  `running` only when the draw stays at or above `idleBelowWatts` for `START_AFTER`, and the end counts
  from the fall; the pauses of the washing machine (one or two samples, then 27 W for minutes) still
  carry the cycle on. A turn of a minute or longer above `runningAboveWatts` after the end would still
  start a new cycle (discarded as too short) - raise the dryer's `runningAboveWatts` (it dries at about
  480 W) if that is ever seen. An invalid sample or an unanswered poll never reaches the detector: time passes
  only with valid samples. `Transition.Acknowledged` is the one transition both the acknowledgement
  (HAS-279) and the tag claim (HAS-343) take.
- **`appliance_run`**: inserted at the start of a cycle (a restart of the pod goes on with it),
  completed at the end (`finished_at` is the fall of the draw, not the moment the end was certain),
  deleted when discarded. A failed write is a WARN and changes nothing in memory; a finish without
  the row of its start inserts the whole row. The row of a finish is matched by its id and its start at
  microsecond precision (what `timestamptz` stores); the sample times are whole seconds anyway. The energy and the highest draw count from the start
  of the pod for a cycle restored as running. `awaitingSince` of a restored finished cycle is its end
  plus `finishedAfterMinutes`.
- **The messages of a finished cycle (HAS-279)** - `Reminders.check` judges one cycle from its row:
  not announced yet (`notified_at` null) - `appliance finished` at once, quiet hours or not (owner,
  2026-10-25); announced - `appliance reminder` with its number every `reminderEveryMinutes` after
  `last_message_at`, at most `maxReminders`, never in the quiet hours (`ReminderSettings.isQuiet`:
  `quietFrom` included, `quietTo` excluded, a span may cross midnight, equal times are no quiet hours);
  acknowledged - nothing. It runs every minute (`MonitorSchedule.remind`, bounded 50 s) for every tracked
  appliance in `finished`, so the finish is announced at most a minute after the end - never from the
  sampling pass, which a slow broker (30 s per confirm) would stall past its 25 s bound. The finish carries
  the end of the cycle as `occurredAt`, also when repeated; reminders and the acknowledgement the time they
  go. A finished row without `notified_at` is announced however old it is (none on the cluster at V3) -
  bound it if that ever matters. Message
  first, row after: a message that did not get through (`HouseholdNotifier` answers false - a broker
  failure or no confirm within 30 s, a WARN) leaves the row as it was and goes again a minute later;
  a failed write repeats the message rather than lose it.
- **The acknowledgement**: `POST /{id}/acknowledge` - `ApplianceMonitor.acknowledge` writes
  `acknowledged_at` first (a failure answers 500 and leaves the cycle waiting and reminded; a row gone from
  the database acknowledges in memory), then moves the detector to idle and forgets the row only if it is
  still the appliance's (`clearRunId` by value - the sampling pass may have stored a new cycle meanwhile), then publishes `appliance acknowledged` with the number of reminders sent (no
  `acknowledgePath`; notification-service decides which transports carry it). Not watched: 404; nothing
  finished waiting: 409 `NOTHING_TO_ACKNOWLEDGE` (a second press). Once HAS-343 claims by tag, this
  endpoint stays as the administrator's mute. **Who acknowledged it** (HAS-347, `?member=`, sent by the
  dashboard from the profile of its personal link): `ApplianceService` checks the name against `GET
  /home/household/profiles` of database-service (`HouseholdProfilesClient` - the switched-on members, no
  phones), whatever the case, before anything is acknowledged - `400 UNKNOWN_MEMBER` for a name it does not
  have; a failed read of the profiles is a WARN and acknowledges without a name (a name is information for
  the message, never a reason to keep a cycle waiting). The name the registry has goes into
  `acknowledged_by` (V4) and into `HouseholdNotification.member` of smart-home-sdk 1.14.0, which
  notification-service >= 0.7.2 words ("... - potwierdza Liwia"); an older notification-service ignores
  it. Not a proof of the chore - that is the tag claim of HAS-343. **A cycle still `finishing`** (HAS-348,
  owner 2026-10-26: somebody heard the end and emptied it before the message): `acknowledgeFinishing` of the
  detector goes from FINISHING straight to IDLE, so neither the reminder pass (it takes FINISHED only) nor the
  sampling pass can announce it, then `RunStore.finishAcknowledged` writes the end (the fall) and the
  acknowledgement in **one** save - no check of the reminders can find the row finished and not acknowledged -
  and only `appliance acknowledged` (reminder 0, the member) goes out: no `finished`, no reminder (owner). A
  failed write answers the error and `reopen`s the detector at the same fall, so the next sample ends the cycle
  as usual, announced. Shorter than `minRunMinutes`: 409. Pressed in a pause of a washing machine (also
  `finishing`) it ends the cycle early; the rest of the program is a new cycle, announced on its own
  (accepted). **A finished cycle a new one replaced**
  (`Started.replacedUnacknowledged`) is acknowledged in its row at the start of the new one, without a
  message (`RunStore.closeReplaced`, before the new row is inserted).
- **`appliance_sample`**: every valid sample (about 2880 rows a day per appliance), deleted after
  `appliance.sample-retention` (`ApplianceProperties`, 14d, 2-90 days, a bare number is days) every
  night at 03:20 (`SampleRetention`, bounded 15 min).
- **Times**: `Instant` inside and `timestamptz` in the database; the API answers and takes the
  clock of the house without an offset (`LOCAL_DATE_TIME` of `ApplianceController`, an offset is a
  400). `NOT_FOUND_APPLIANCE`, `NOTHING_TO_ACKNOWLEDGE` and `UNKNOWN_MEMBER` (`ApplianceError`) are wire contract, as are the words of
  `ApplianceState` - both pinned by `ApplianceErrorTest`.
- **Layers of the API**: `ApplianceController` only takes the request; `ApplianceService` holds the logic -
  the lookup of a watched appliance (404), the rules of a range (a `400` as `ResponseStatusException`, as
  `WeatherHistoryService`), the clock of the house and the reply models - and is the one that reads the
  monitor and the repositories (owner, 2026-10-25: no controller depends on a repository).
- **What runs at the same time**: the refresh and the sampling pass run on two threads of the
  scheduler pool and the API on Netty - `TrackedAppliance` holds atomic references, the detector is
  synchronized, the row and the samples are written by the sampling pass only. The database is
  written one appliance after the other: a pass holds one connection of the pool of 2 at a time,
  the restore of the refresh and the reads of the API take the other.
- **Tests use `mockwebserver3`**, never the legacy `mockwebserver` (JUnit 4) of the reference.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`.
