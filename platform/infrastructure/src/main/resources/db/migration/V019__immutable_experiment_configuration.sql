-- New evidence manifests refer to this immutable source instead of copying it per trial.
create function guard_experiment_configuration() returns trigger language plpgsql as $$
begin
    if old.snapshot is distinct from new.snapshot then
        if not (old.status='DELETING' and new.status='DELETED' and new.snapshot='{}'::jsonb
            and not exists(select 1 from evidence_snapshots s join trials t on t.id=s.trial_id where t.experiment_id=old.id)) then
            raise exception 'Frozen experiment configuration is immutable';
        end if;
    end if;
    return new;
end $$;
create trigger immutable_experiment_configuration before update on experiments
    for each row execute function guard_experiment_configuration();
