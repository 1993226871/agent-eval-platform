package io.agenteval.model;
import java.math.BigInteger;
import java.util.UUID;
public record PriceVersion(UUID id,long inputRate,long outputRate,long denominator) {
    public PriceVersion { if(id==null || inputRate<0 || outputRate<0 || inputRate==0 && outputRate==0 || denominator<=0) throw new IllegalArgumentException("Invalid credit price"); }
    public long credits(long inputTokens,long outputTokens) {
        if(inputTokens<0 || outputTokens<0) throw new IllegalArgumentException("Invalid token usage");
        var sum=BigInteger.valueOf(inputTokens).multiply(BigInteger.valueOf(inputRate)).add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(outputRate)));
        var divisor=BigInteger.valueOf(denominator);
        try { return sum.add(divisor).subtract(BigInteger.ONE).divide(divisor).longValueExact(); }
        catch(ArithmeticException overflow) { throw new IllegalArgumentException("Credit calculation overflow"); }
    }
}
