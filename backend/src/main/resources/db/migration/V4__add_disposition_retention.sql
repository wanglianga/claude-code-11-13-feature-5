-- V4：处置记录补充保留期到期日列（WAIT_RETENTION 调度需要）
ALTER TABLE compliance.disposition_record ADD COLUMN retention_until date;
