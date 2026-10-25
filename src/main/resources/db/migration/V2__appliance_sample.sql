-- Every valid sample of the draw of a plug, every 30 s (HAS-278): the chart of the dashboard and what the
-- owner tunes the thresholds of the registry from. Kept 14 days (appliance.sample-retention), deleted every
-- night - about 2880 rows a day per appliance. An invalid sample or a failed poll is not stored.
CREATE TABLE appliance_sample
(
    id        BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    device_id BIGINT             NOT NULL,
    at        TIMESTAMPTZ        NOT NULL,
    watts     NUMERIC(7, 2)      NOT NULL
);

CREATE INDEX appliance_sample_device_at_idx ON appliance_sample (device_id, at);
CREATE INDEX appliance_sample_at_idx ON appliance_sample (at);
