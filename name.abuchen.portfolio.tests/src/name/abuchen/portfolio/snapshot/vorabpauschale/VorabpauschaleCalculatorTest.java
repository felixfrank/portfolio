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

    @Test
    public void testMidYearPurchaseReducedByTwelfths()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2024-03-10", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        // 177.10 * 10/12 = 147.58 (March -> (13-3)/12)
        assertThat(r.getGrossBasisertrag(), is(Money.of("EUR", 147_58)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 147_58)));
    }

    @Test
    public void testDistributionsZeroOutVorabpauschale()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);
        // 200.00 EUR dividend in 2024 exceeds the 177.10 Basisertrag
        // -> vorabpauschale = max(0, 177.10 - 200.00) = 0
        new AccountBuilder().dividend("2024-06-01", 200_00, security).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getDistributions(), is(Money.of("EUR", 200_00)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 0)));
    }

    @Test
    public void testDownYearCapsToZero()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(90.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        // cap = max(0, 9000 - 10000 + 0) = 0
        assertThat(r.getCappedBasisertrag(), is(Money.of("EUR", 0)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
    }
}
