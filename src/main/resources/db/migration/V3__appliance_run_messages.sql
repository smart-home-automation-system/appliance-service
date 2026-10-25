-- The messages of a finished cycle (HAS-279): notified_at - when the household was told it has finished
-- (NULL until the message got through, so a failed one is sent again), last_message_at - the latest message
-- of the cycle, the finish or a reminder; the next reminder is due reminderEveryMinutes after it, and one
-- due in the quiet hours waits for their end. reminders_sent (V1) counts the reminders, so a restart of the
-- pod resends nothing. A cycle a new one replaced gets acknowledged_at without a message.
ALTER TABLE appliance_run
    ADD COLUMN notified_at     TIMESTAMPTZ,
    ADD COLUMN last_message_at TIMESTAMPTZ;
