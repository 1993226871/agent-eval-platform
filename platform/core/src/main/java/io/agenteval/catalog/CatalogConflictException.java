package io.agenteval.catalog;
public final class CatalogConflictException extends IllegalStateException {
    public CatalogConflictException() { super("Catalog revision conflict"); }
}
