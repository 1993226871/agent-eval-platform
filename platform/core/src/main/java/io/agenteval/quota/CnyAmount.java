package io.agenteval.quota;
import java.math.BigDecimal;
/** Integer micro-yuan, never floating-point currency. */
public final class CnyAmount {
    private CnyAmount() {}
    public static long parse(String amount) {
        if(amount==null || !amount.matches("-?(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,6})?")) throw new IllegalArgumentException("Invalid CNY amount");
        try { return new BigDecimal(amount).movePointRight(6).longValueExact(); }
        catch(ArithmeticException invalid) { throw new IllegalArgumentException("CNY amount exceeds supported range"); }
    }
}
