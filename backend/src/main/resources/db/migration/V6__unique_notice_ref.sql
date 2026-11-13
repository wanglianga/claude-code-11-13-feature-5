-- V6：通知号唯一约束，删除作业重放时 ON CONFLICT 才能真正幂等
ALTER TABLE compliance.notification_outbox
    ADD CONSTRAINT uq_notification_notice_ref UNIQUE (notice_ref);
