package io.agenteval.quota;
/** A wallet and model tariff use different units; never silently convert historical money. */
public final class BillingUnitMismatchException extends RuntimeException {}
