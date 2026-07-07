package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    @Test
    public void testTeilfreistellungAndTwoLotAllocation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2022-06-01", 100 * SHARE, 10_000_00) //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), new BigDecimal("0.70"), eur);

        // gross = 200sh * 100.00 * 0.0253 * 0.70 = 354.20; taxable = *0.70 = 247.94
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 354_20)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 247_94)));
        assertThat(r.getLots().size(), is(2));
        assertThat(r.getLots().get(0).getContribution(), is(Money.of("EUR", 177_10)));
        assertThat(r.getLots().get(1).getContribution(), is(Money.of("EUR", 177_10)));
    }

    @Test
    public void testMissingYearStartPriceWarns()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client); // no prices at all
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(r.getWarnings().isEmpty(), is(false));
    }

    @Test
    public void testContributionsSumToTotal()
    {
        // Three equal lots at 100.01 EUR: each lot's Basisertrag is
        // 100.01 * 100 * 0.0253 * 0.70 = 177.11771, so the total Vorabpauschale
        // in cents (53135) is not divisible by three. The first two lots round
        // to 177.12 and the last lot must take the exact remainder so that the
        // per-lot breakdown still sums to the headline Vorabpauschale.
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.01)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2021-06-01", 100 * SHARE, 10_001_00) //
                        .buy(security, "2022-06-01", 100 * SHARE, 10_001_00) //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_001_00) //
                        .addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getLots().size(), is(3));

        long sum = 0;
        for (LotContribution lot : r.getLots())
            sum += lot.getContribution().getAmount();

        assertThat(Money.of("EUR", sum), is(r.getVorabpauschale()));
    }

    @Test
    public void testCrossCurrencyConversion()
    {
        // A USD security in a EUR portfolio: the year-start price must be
        // converted to EUR before the Basisertrag is computed. TestCurrencyConverter
        // supports USD<->EUR for dates around the 2014-12-31..2015-01-16 window.
        Client client = new Client();
        Security security = new SecurityBuilder("USD") //
                        .addPrice("2015-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2015-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2014-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2015,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        // year-start value = converted per-share price * 100 shares (proves the
        // conversion path feeds the calculation instead of treating USD as EUR)
        Money perShareEur = eur.convert(LocalDate.of(2015, 1, 1), Money.of("USD", 100_00));
        assertThat(r.getYearStartValue(), is(Money.of("EUR", perShareEur.getAmount() * 100)));

        // and the result differs from the EUR-identity figure (177.10) it would
        // have produced if the USD price had been used verbatim as EUR
        assertThat(r.getVorabpauschale().getAmount(), greaterThan(0L));
        assertThat(r.getVorabpauschale(), is(not(Money.of("EUR", 177_10))));
    }
}
