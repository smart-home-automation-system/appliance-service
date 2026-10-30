-- Who emptied a cycle and who was at home (HAS-344). All nullable: a cycle not completed yet, or a snapshot that could
-- not be taken, has none - null is "not known", an empty list "nobody".
-- present_at_finish / present_at_completion: the names presence-service gave as present when the cycle finished and
--   when it was completed (the first confirmation or the mute);
-- completed_at, completion_source: when and how the cycle was completed first - a tag read (nfc), "Emptied" in the
--   application with a member (app), or a mute without one (mute);
-- attributed_to, attribution: whose chore it was - one member (single), two within appliance.attribution-window
--   (shared, half each), or nobody (unattributed). Owner 2026-10-30: the tag and the application are equal ways to
--   confirm; only a mute without a member is unattributed, and a later confirmation within the claim window still
--   attributes it.
ALTER TABLE appliance_run
    ADD COLUMN present_at_finish     TEXT[],
    ADD COLUMN completed_at          TIMESTAMPTZ,
    ADD COLUMN completion_source     VARCHAR(10) CHECK (completion_source IN ('nfc', 'app', 'mute')),
    ADD COLUMN attributed_to         TEXT[],
    ADD COLUMN attribution           VARCHAR(15) CHECK (attribution IN ('single', 'shared', 'unattributed')),
    ADD COLUMN present_at_completion TEXT[];
