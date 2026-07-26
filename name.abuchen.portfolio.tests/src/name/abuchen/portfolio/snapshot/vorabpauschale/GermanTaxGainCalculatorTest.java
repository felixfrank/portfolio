package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.Test;

import java.time.LocalDateTime;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.VorabpauschaleEntry;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class GermanTaxGainCalculatorTest
{
    private static final long SHARE = Values.Share.factor();
    private final TestCurrencyConverter eur = new TestCurrencyConverter(CurrencyUnit.EUR);

    private VorabpauschaleEntry ledger(Security security, int year, long vorabCents)
    {
        return new VorabpauschaleEntry(security, year, new BigDecimal("2.53"), BigDecimal.ONE,
                        Money.of("EUR", 10_000_00), Money.of("EUR", 0), Money.of("EUR", vorabCents),
                        Money.of("EUR", vorabCents), Money.of("EUR", vorabCents),
                        Instant.parse((year + 1) + "-01-02T00:00:00Z"));
    }

    @Test
    public void testAccumulatedVorabpauschaleRaisesBasis()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 13_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2024, 177_10));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 2_822_90)));
        assertThat(r.getTotalTaxableGain(), is(Money.of("EUR", 2_822_90)));
    }

    @Test
    public void testFifoConsumesOldestLotWithItsAccumulation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2022-03-01", 100 * SHARE, 10_000_00) //
                        .buy(security, "2023-06-01", 100 * SHARE, 12_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 15_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2022, 100_00));
        client.addVorabpauschaleEntry(ledger(security, 2023, 200_00));
        client.addVorabpauschaleEntry(ledger(security, 2024, 300_00));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getShares(), is(100 * SHARE));
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 376_32)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 4_623_68)));
    }

    @Test
    public void testPartialSaleSpansTwoLots()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2023-01-01", 60 * SHARE, 6_000_00) //
                        .buy(security, "2024-01-01", 40 * SHARE, 5_000_00) //
                        .sell(security, "2025-06-01", 80 * SHARE, 10_000_00) //
                        .addTo(client);
        // no ledger entries -> isolate the FIFO proceeds/cost split
        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getLots().size(), is(2));           // lot1 fully (60), lot2 partially (20)
        assertThat(sale.getShares(), is(80 * SHARE));
        assertThat(sale.getProceeds(), is(Money.of("EUR", 10_000_00)));
        assertThat(sale.getCost(), is(Money.of("EUR", 8_500_00)));   // 6000 + (5000*20/40)=2500
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 1_500_00))); // (7500-6000) + (2500-2500)
    }

    @Test
    public void testSameYearBuyAndSellHasNoAccumulation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2025-02-01", 50 * SHARE, 5_000_00) //
                        .sell(security, "2025-09-01", 50 * SHARE, 5_500_00) //
                        .addTo(client);

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 500_00)));
    }

    @Test
    public void testTeilfreistellungAppliedToGain()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 13_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2024, 177_10));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> new BigDecimal("0.70"), eur);

        // 2822.90 * 0.70 = 1976.03
        assertThat(r.getSales().get(0).getTaxableGain(), is(Money.of("EUR", 1_976_03)));
    }

    @Test
    public void testSaleUsesOwnAccountLotsAndReportsAccount()
    {
        // same fungible security held in two accounts; a sale in B must take B's
        // own (more expensive) lot, not A's older cheaper lot (pooled FIFO would)
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        Portfolio a = new PortfolioBuilder().buy(security, "2022-03-01", 100 * SHARE, 10_000_00).addTo(client);
        a.setName("A");
        Portfolio b = new PortfolioBuilder() //
                        .buy(security, "2024-01-01", 100 * SHARE, 12_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 15_000_00) //
                        .addTo(client);
        b.setName("B");

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccount(), is(b));
        assertThat(sale.getCost(), is(Money.of("EUR", 12_000_00)));      // B's own lot
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 3_000_00)));
        assertThat(r.getTotalTaxableGain(), is(Money.of("EUR", 3_000_00)));
    }

    @Test
    public void testTransferCarriesCostBasisAndAccumulationToNewAccount()
    {
        // buy in A, accrue 2023 Vorabpauschale while in A, transfer A->B in 2024,
        // sell in B in 2025: cost basis, original purchase date and the accrued
        // Vorabpauschale must all follow the shares into B
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        Portfolio a = new PortfolioBuilder().buy(security, "2022-03-01", 100 * SHARE, 10_000_00).addTo(client);
        a.setName("A");
        Portfolio b = new PortfolioBuilder().sell(security, "2025-06-01", 100 * SHARE, 13_000_00).addTo(client);
        b.setName("B");

        PortfolioTransferEntry transfer = new PortfolioTransferEntry(a, b);
        transfer.setSecurity(security);
        transfer.setDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        transfer.setShares(100 * SHARE);
        transfer.setAmount(10_000_00);
        transfer.setCurrencyCode(CurrencyUnit.EUR);
        transfer.insert();

        client.addVorabpauschaleEntry(ledger(security, 2023, 150_00));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccount(), is(b));
        assertThat(sale.getCost(), is(Money.of("EUR", 10_000_00)));
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 150_00)));
        assertThat(sale.getLots().get(0).getPurchaseDate().toString(), is("2022-03-01"));
        // (13000 - 10000 - 150) = 2850.00
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 2_850_00)));
    }

    @Test
    public void testLossWithExemptionAndYearFiltering()
    {
        Client client = new Client();
        Security a = new SecurityBuilder("EUR").addTo(client);
        Security b = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(a, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(a, "2025-06-01", 100 * SHARE, 8_000_00) //
                        .buy(b, "2022-01-01", 10 * SHARE, 1_000_00) //
                        .sell(b, "2024-01-01", 10 * SHARE, 1_500_00) // different year -> excluded
                        .addTo(client);

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> new BigDecimal("0.70"), eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getSecurity(), is(a));
        // -2000.00 * 0.70 = -1400.00
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", -1_400_00)));
        assertThat(r.getTotalTaxableGain(), is(Money.of("EUR", -1_400_00)));
    }
}
