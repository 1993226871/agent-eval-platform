package io.agenteval.catalog;
import java.util.UUID;
public record PublishedVersionSummary(UUID id,int number,long draftRevision,String contentHash) {}
