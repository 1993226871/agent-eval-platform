CREATE TABLE trace_events (
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    event_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK(sequence>0),
    event_json JSONB NOT NULL,
    content_hash CHAR(64) NOT NULL,
    origin VARCHAR(32) NOT NULL DEFAULT 'worker_reported' CHECK(origin IN ('worker_reported','platform_observed','verifier_observed')),
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(workspace_id,event_id),
    UNIQUE(trial_id,sequence),
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id)
);
CREATE INDEX events_trial_sequence_idx ON trace_events(workspace_id,trial_id,sequence);
CREATE TABLE spans (
    id BIGSERIAL PRIMARY KEY,
    workspace_id UUID NOT NULL,
    trial_id UUID NOT NULL,
    span_id CHAR(16) NOT NULL,
    parent_span_id CHAR(16),
    name VARCHAR(1024) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'INCOMPLETE',
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    input JSONB NOT NULL DEFAULT 'null',
    output JSONB NOT NULL DEFAULT 'null',
    duration_ms BIGINT,
    origin VARCHAR(32) NOT NULL DEFAULT 'worker_reported',
    UNIQUE(workspace_id,trial_id,span_id),
    FOREIGN KEY(trial_id,workspace_id) REFERENCES trials(id,workspace_id)
);
CREATE INDEX spans_trial_page_idx ON spans(workspace_id,trial_id,id);
