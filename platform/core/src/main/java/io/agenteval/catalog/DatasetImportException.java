package io.agenteval.catalog;
public final class DatasetImportException extends IllegalArgumentException {
    private final int line;
    public DatasetImportException(int line) { super("Invalid JSONL at line "+line); this.line=line; }
    public int line() { return line; }
}
