package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class VorabpauschaleCalculatorTest
{
    private static final long SHARE = Values.Share.factor();
    private final TestCurrencyConverter eur = new TestCurrencyConverter(CurrencyUnit.EUR);

    @Test
    public void testBuyAndHoldFullYear()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getGrossBasisertrag(), is(Money.of("EUR", 177_10)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 177_10)));
        assertThat(r.getLots().size(), is(1));
    }
}
