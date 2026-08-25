-- Recurring bills, and the in-app notification feed.
--
-- Notifications are persisted rather than existing only as a WebSocket push. A push reaches
-- whoever happens to be connected at that instant; anyone who was closed, asleep, or on a
-- train receives nothing and never learns of it. The socket is the fast path, this table is
-- the record.

CREATE TABLE bills (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- A bill is usually filed under a category once paid, so the reminder can name it.
    category_id    UUID           REFERENCES categories (id) ON DELETE SET NULL,
    name           VARCHAR(120)   NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    recurrence     VARCHAR(10)    NOT NULL,
    -- Day of the month the payment falls due. A bill due on the 31st in a 30-day month is
    -- clamped to the last day at read time rather than being stored wrong.
    due_day        INT            NOT NULL,
    -- For YEARLY bills only; NULL on monthly ones.
    due_month      INT,
    -- How many days ahead to warn. Zero means "on the day".
    remind_days_before INT        NOT NULL DEFAULT 3,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    -- The occurrence a reminder was last sent for, not the timestamp it was sent at.
    -- Comparing against the *due date* is what makes the scheduler idempotent: a job that
    -- runs twice in a day, or catches up after downtime, cannot send the same reminder twice.
    last_reminded_for DATE,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT bills_recurrence_check CHECK (recurrence IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT bills_amount_check CHECK (amount > 0),
    CONSTRAINT bills_due_day_check CHECK (due_day BETWEEN 1 AND 31),
    CONSTRAINT bills_due_month_check CHECK (due_month IS NULL OR due_month BETWEEN 1 AND 12),
    CONSTRAINT bills_remind_days_check CHECK (remind_days_before BETWEEN 0 AND 30),
    -- A yearly bill without a month has no due date; a monthly one with a month is ambiguous.
    CONSTRAINT bills_month_matches_recurrence CHECK (
        (recurrence = 'YEARLY' AND due_month IS NOT NULL)
        OR (recurrence = 'MONTHLY' AND due_month IS NULL))
);

-- The scheduler scans for active bills across all users, so the index leads with `active`.
CREATE INDEX idx_bills_active ON bills (active) WHERE active;
CREATE INDEX idx_bills_user ON bills (user_id);


CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(30)  NOT NULL,
    title      VARCHAR(160) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    -- Type-specific detail, so a client can deep-link to the budget or bill in question
    -- without the table growing a column per notification kind.
    payload    JSONB,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT notifications_type_check CHECK (type IN ('BUDGET_THRESHOLD', 'BILL_DUE'))
);

-- The feed is "my notifications, newest first", and the unread count is the badge.
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TRIGGER bills_set_updated_at
    BEFORE UPDATE ON bills
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
