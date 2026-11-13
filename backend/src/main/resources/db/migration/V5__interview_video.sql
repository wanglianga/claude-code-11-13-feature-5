-- V5：快照冻结后新接入的资产（用于演示增补工单：原快照不可追溯改写）
CREATE TABLE biz.interview_video (
    video_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    object_key   varchar(256) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

INSERT INTO biz.interview_video (subject_hash, object_key)
SELECT subject_hash, 'video/interview-' || subject_hash || '.mp4'
FROM biz.account
WHERE subject_hash IN (
    'd6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48',
    '8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7');
