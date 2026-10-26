package io.agenteval.quota;
import java.util.List;
public record QuotaLedgerPage(List<QuotaEntry> items,String nextCursor) { public QuotaLedgerPage { items=List.copyOf(items); } }
