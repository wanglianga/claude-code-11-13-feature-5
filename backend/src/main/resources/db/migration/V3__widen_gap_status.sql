-- V3：修正断链确认状态列长度（CONFIRMED_IN_SCOPE/OUT_SCOPE 为 20 字符）
ALTER TABLE compliance.lineage_gap ALTER COLUMN status TYPE varchar(24);
