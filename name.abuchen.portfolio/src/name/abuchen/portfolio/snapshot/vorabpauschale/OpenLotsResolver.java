package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.snapshot.trades.TradeCollector;

/**
 * Resolves the purchase lots of a security still held as of a given date,
 * aggregated across all of the client's portfolios. Buys and inbound
 * deliveries add shares; sells and outbound deliveries consume shares FIFO.
 * Transfers between the investor's own portfolios do not change the
 * investor-level holding and are ignored so that the original acquisition
 * date of each lot is preserved.
 */
public final class OpenLotsResolver
{
    private OpenLotsResolver()
    {
    }

    public static List<OpenLot> resolve(Client client, Security security, LocalDate asOf)
    {
        List<TransactionPair<?>> all = security.getTransactions(client);
        Collections.sort(all, TradeCollector.BY_DATE_AND_TYPE);

        Deque<long[]> open = new ArrayDeque<>(); // { index into 'purchases', remaining shares }
        List<PortfolioTransaction> purchases = new ArrayList<>();

        for (TransactionPair<?> pair : all)
        {
            if (!(pair.getTransaction() instanceof PortfolioTransaction tx))
                continue;
            if (tx.getDateTime().toLocalDate().isAfter(asOf))
                continue;

            switch (tx.getType())
            {
                case BUY, DELIVERY_INBOUND:
                    purchases.add(tx);
                    open.addLast(new long[] { purchases.size() - 1, tx.getShares() });
                    break;
                case SELL, DELIVERY_OUTBOUND:
                    consume(open, tx.getShares());
                    break;
                case TRANSFER_IN, TRANSFER_OUT:
                    // investor-level no-op
                    break;
                default:
                    break;
            }
        }

        List<OpenLot> result = new ArrayList<>();
        for (long[] entry : open)
        {
            if (entry[1] > 0)
                result.add(new OpenLot(purchases.get((int) entry[0]), entry[1]));
        }
        return result;
    }

    private static void consume(Deque<long[]> open, long shares)
    {
        long remaining = shares;
        while (remaining > 0 && !open.isEmpty())
        {
            long[] lot = open.peekFirst();
            if (lot[1] <= remaining)
            {
                remaining -= lot[1];
                open.removeFirst();
            }
            else
            {
                lot[1] -= remaining;
                remaining = 0;
            }
        }
    }
}
