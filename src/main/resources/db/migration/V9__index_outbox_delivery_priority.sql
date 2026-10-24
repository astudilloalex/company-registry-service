-- Preserve V1–V8 and all event history; avoid sorting the pending backlog per poll.
CREATE INDEX ix_company_outbox_due_priority
    ON company_outbox_events (coalesce(next_attempt_at, created_at), created_at, id)
    WHERE status = 'PENDING';
