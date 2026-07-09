package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class OpenLotsResolverTest
{
    private static final long SHARE = Values.Share.factor(); // 1 share

    @Test
    public void testFifoPartialSaleAndDateBound()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        new PortfolioBuilder() //
                        .buy(security, "2023-05-01", 10 * SHARE, 100_00) //
                        .buy(security, "2024-03-10", 10 * SHARE, 120_00) //
                        .sell(security, "2024-06-01", 12 * SHARE, 150_00) //
                        .buy(security, "2025-02-01", 5 * SHARE, 60_00) //
                        .addTo(client);

        // as of end of 2024: 10 + 10 - 12 = 8 shares; FIFO leaves 8 from the 2024-03-10 lot
        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, LocalDate.of(2024, 12, 31));

        assertThat(lots.size(), is(1));
        assertThat(lots.get(0).getShares(), is(8 * SHARE));
        assertThat(lots.get(0).getPurchase().getDateTime().toLocalDate(), is(LocalDate.of(2024, 3, 10)));
    }

    @Test
    public void testSaleInOtherPortfolioDoesNotConsumeForeignLots()
    {
        // a fungible security traded independently in a second portfolio: the
        // round-trip nets to zero and must not consume the long-term lot of the
        // first portfolio (pooled FIFO would have aged-down the holding)
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        new PortfolioBuilder().buy(security, "2022-06-01", 100 * SHARE, 10_000_00).addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2025-05-01", 50 * SHARE, 6_000_00) //
                        .sell(security, "2025-09-01", 50 * SHARE, 6_500_00) //
                        .addTo(client);

        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, LocalDate.of(2025, 12, 31));

        long total = lots.stream().mapToLong(OpenLot::getShares).sum();
        assertThat(total, is(100 * SHARE));
        assertThat(lots.size(), is(1));
        assertThat(lots.get(0).getShares(), is(100 * SHARE));
        assertThat(lots.get(0).getPurchase().getDateTime().toLocalDate(), is(LocalDate.of(2022, 6, 1)));
    }

    @Test
    public void testTransfersPreserveOriginalLot()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        Portfolio source = new PortfolioBuilder().buy(security, "2022-01-10", 10 * SHARE, 100_00).addTo(client);
        Portfolio target = new PortfolioBuilder().addTo(client);

        // Transfer all 10 shares from source to target in 2024
        PortfolioTransferEntry transfer = new PortfolioTransferEntry(source, target);
        transfer.setSecurity(security);
        transfer.setDate(LocalDateTime.of(2024, 6, 1, 0, 0));
        transfer.setShares(10 * SHARE);
        transfer.setAmount(100_00);
        transfer.setCurrencyCode(CurrencyUnit.EUR);
        transfer.insert();

        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, LocalDate.of(2024, 12, 31));

        assertThat(lots.size(), is(1));
        assertThat(lots.get(0).getShares(), is(10 * SHARE));
        assertThat(lots.get(0).getPurchase().getDateTime().toLocalDate(), is(LocalDate.of(2022, 1, 10)));
    }
}
