package io.agenteval.quota;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CnyAmountTest {
    @Test void preservesMicroYuanAndRejectsInvalidOrOverflowingCurrency() {
        assertThat(CnyAmount.parse("5")).isEqualTo(5_000_000);
        assertThat(CnyAmount.parse("0.000015")).isEqualTo(15);
        assertThat(CnyAmount.parse("-1.01")).isEqualTo(-1_010_000);
        for(String value:new String[]{"NaN","1e5","0.0000001","01","9999999999999"})
            assertThatThrownBy(()->CnyAmount.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
