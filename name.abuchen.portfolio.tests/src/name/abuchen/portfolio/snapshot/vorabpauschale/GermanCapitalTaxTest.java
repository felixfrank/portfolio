package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class GermanCapitalTaxTest
{
    private static final Money BASE = Money.of("EUR", 10_000_00); // 10,000.00 EUR

    @Test
    public void testNoChurchTaxIs26Point375Percent()
    {
        var tax = GermanCapitalTax.compute(BASE, BigDecimal.ZERO);
        assertThat(tax.getKapitalertragsteuer(), is(Money.of("EUR", 2_500_00))); // 25%
        assertThat(tax.getSoli(), is(Money.of("EUR", 137_50)));                  // 5.5% of KapESt
        assertThat(tax.getKirchensteuer(), is(Money.of("EUR", 0)));
        assertThat(tax.getTotal(), is(Money.of("EUR", 2_637_50)));               // 26.375%
    }

    @Test
    public void testChurchTax8Percent()
    {
        var tax = GermanCapitalTax.compute(BASE, GermanCapitalTax.CHURCH_RATE_8);
        assertThat(tax.getKapitalertragsteuer(), is(Money.of("EUR", 2_450_98))); // base / 4.08
        assertThat(tax.getSoli(), is(Money.of("EUR", 134_80)));
        assertThat(tax.getKirchensteuer(), is(Money.of("EUR", 196_08)));         // 8% of KapESt
        assertThat(tax.getTotal(), is(Money.of("EUR", 2_781_86)));               // 27.8186%
    }

    @Test
    public void testChurchTax9Percent()
    {
        var tax = GermanCapitalTax.compute(BASE, GermanCapitalTax.CHURCH_RATE_9);
        assertThat(tax.getKapitalertragsteuer(), is(Money.of("EUR", 2_444_99))); // base / 4.09
        assertThat(tax.getSoli(), is(Money.of("EUR", 134_47)));
        assertThat(tax.getKirchensteuer(), is(Money.of("EUR", 220_05)));         // 9% of KapESt
        assertThat(tax.getTotal(), is(Money.of("EUR", 2_799_51)));               // 27.9951%
    }

    @Test
    public void testLossProducesNegativeOffsettingTax()
    {
        var tax = GermanCapitalTax.compute(Money.of("EUR", -10_000_00), GermanCapitalTax.CHURCH_RATE_9);
        assertThat(tax.getKapitalertragsteuer(), is(Money.of("EUR", -2_444_99)));
        assertThat(tax.getTotal(), is(Money.of("EUR", -2_799_51)));
    }
}
