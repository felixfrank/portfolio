package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.Values;
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
    private static final int IDX = 0;
    private static final int REMAINING = 1;

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
                    long leftover = consume(open, tx.getShares());
                    if (leftover > 0)
                        PortfolioLog.warning(MessageFormat.format(
                                        "Vorabpauschale: more shares sold ({0}) than held for security {1} on {2}",
                                        Values.Share.format(leftover), security.getName(),
                                        Values.DateTime.format(tx.getDateTime())));
                    break;
                case TRANSFER_IN, TRANSFER_OUT:
                    // investor-level no-op
                    break;
                default:
                    throw new UnsupportedOperationException(tx.getType().name());
            }
        }

        List<OpenLot> result = new ArrayList<>();
        for (long[] entry : open)
        {
            if (entry[REMAINING] > 0)
                result.add(new OpenLot(purchases.get((int) entry[IDX]), entry[REMAINING]));
        }
        return result;
    }

    private static long consume(Deque<long[]> open, long shares)
    {
        long remaining = shares;
        while (remaining > 0 && !open.isEmpty())
        {
            long[] lot = open.peekFirst();
            if (lot[REMAINING] <= remaining)
            {
                remaining -= lot[REMAINING];
                open.removeFirst();
            }
            else
            {
                lot[REMAINING] -= remaining;
                remaining = 0;
            }
        }
        return remaining;
    }
}
