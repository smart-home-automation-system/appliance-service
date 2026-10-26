-- Who acknowledged a finished cycle in the application (HAS-347): the name of a household member as the
-- registry has it. NULL when nobody was named - a call without a member (Bruno), a cycle a new one replaced,
-- and every row before V4. Information for the message, not a proof of the chore (HAS-343).
ALTER TABLE appliance_run
    ADD COLUMN acknowledged_by VARCHAR(50);
