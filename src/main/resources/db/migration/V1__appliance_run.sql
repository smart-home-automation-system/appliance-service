-- The cycles of the appliances (HAS-278): one row per cycle, inserted when it starts - a restart of the pod
-- in the middle of a cycle goes on with it - and completed when it has finished. A run shorter than
-- minRunMinutes is deleted, it was no cycle. device_id is the id of the plug in the device registry of
-- database-service, so there is no foreign key, and the rows of a removed appliance stay.
-- Instants (timestamptz): the end of a cycle and the reminders of HAS-279 are judged by the time passed,
-- and house time runs an hour backwards the night the clocks go back.
-- finished_at is the fall below idleBelowWatts, not the moment the end was certain; energy_wh and max_watts
-- are written with it. acknowledged_at and reminders_sent belong to HAS-279.
CREATE TABLE appliance_run
(
    id              BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    device_id       BIGINT             NOT NULL,
    started_at      TIMESTAMPTZ        NOT NULL,
    finished_at     TIMESTAMPTZ,
    energy_wh       BIGINT,
    max_watts       INTEGER,
    acknowledged_at TIMESTAMPTZ,
    reminders_sent  INTEGER            NOT NULL DEFAULT 0
);

-- the latest run of an appliance (the state restored at the start) and the runs in a span of time
CREATE INDEX appliance_run_device_started_idx ON appliance_run (device_id, started_at);
