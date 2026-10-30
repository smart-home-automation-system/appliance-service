-- The NFC tags on the appliances and the chores claimed with them (HAS-343).
-- appliance_tag: one row per tag that was read, the UID as 14 upper-case hex characters. last_counter is the read
-- counter of the latest accepted read - a read whose counter is not above it is a replay. device_id is the appliance
-- the tag was on at that read (the map appliance.nfc.tags of the manifest decides it). Written only after a read
-- the key verified.
CREATE TABLE appliance_tag
(
    uid           VARCHAR(14) PRIMARY KEY,
    device_id     BIGINT      NOT NULL,
    last_counter  INTEGER     NOT NULL,
    last_claim_at TIMESTAMPTZ NOT NULL
);

-- appliance_claim: who emptied which cycle. source nfc is a verified tag read (with its counter); app is kept for an
-- attribution from the application, which nothing writes today (owner 2026-10-24: the button of the application
-- proves nothing). One cycle may have several claims - two members emptying it together (HAS-344).
CREATE TABLE appliance_claim
(
    id         BIGSERIAL PRIMARY KEY,
    run_id     BIGINT      NOT NULL REFERENCES appliance_run (id),
    member     VARCHAR(50) NOT NULL,
    source     VARCHAR(10) NOT NULL CHECK (source IN ('nfc', 'app')),
    claimed_at TIMESTAMPTZ NOT NULL,
    counter    INTEGER
);

CREATE INDEX appliance_claim_run_id ON appliance_claim (run_id);
CREATE INDEX appliance_claim_member_claimed_at ON appliance_claim (member, claimed_at);
