package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.trades.TradeCollector;

/**
 * Resolves the purchase lots of a security still held as of a given date. FIFO
 * is applied <em>per portfolio</em>: a sale consumes lots of its own portfolio,
 * and a transfer moves lots - preserving their original acquisition date - from
 * the source portfolio to the target portfolio. Resolving FIFO per portfolio
 * (rather than pooled across all portfolios) is essential when the same fungible
 * security is traded independently in more than one portfolio: otherwise a sale
 * in one portfolio would consume the oldest lots of another and distort the
 * surviving acquisition dates (and thus the Zwölftelung of the Vorabpauschale).
 */
public final class OpenLotsResolver
{
    private OpenLotsResolver()
    {
    }

    /** A surviving purchase lot with a mutable remaining share count. */
    private static final class Lot
    {
        private final PortfolioTransaction purchase;
        private long remaining;

        private Lot(PortfolioTransaction purchase, long remaining)
        {
            this.purchase = purchase;
            this.remaining = remaining;
        }
    }

    public static List<OpenLot> resolve(Client client, Security security, LocalDate asOf)
    {
        List<TransactionPair<?>> all = security.getTransactions(client);
        Collections.sort(all, TradeCollector.BY_DATE_AND_TYPE);

        Map<Portfolio, List<Lot>> byPortfolio = new HashMap<>();

        for (TransactionPair<?> pair : all)
        {
            if (!(pair.getTransaction() instanceof PortfolioTransaction tx))
                continue;
            if (tx.getDateTime().toLocalDate().isAfter(asOf))
                continue;

            Portfolio portfolio = (Portfolio) pair.getOwner();
            List<Lot> lots = byPortfolio.computeIfAbsent(portfolio, p -> new ArrayList<>());

            switch (tx.getType())
            {
                case BUY, DELIVERY_INBOUND:
                    lots.add(new Lot(tx, tx.getShares()));
                    break;
                case SELL, DELIVERY_OUTBOUND:
                    long leftover = take(lots, tx.getShares(), null);
                    if (leftover > 0)
                        PortfolioLog.warning(MessageFormat.format(
                                        "Vorabpauschale: more shares sold ({0}) than held for security {1} on {2}",
                                        Values.Share.format(leftover), security.getName(),
                                        Values.DateTime.format(tx.getDateTime())));
                    break;
                case TRANSFER_IN:
                    moveLots(byPortfolio, pair, tx, security);
                    break;
                case TRANSFER_OUT:
                    // handled via the matching TRANSFER_IN
                    break;
                default:
                    throw new UnsupportedOperationException(tx.getType().name());
            }
        }

        List<OpenLot> result = new ArrayList<>();
        for (List<Lot> lots : byPortfolio.values())
            for (Lot lot : lots)
                if (lot.remaining > 0)
                    result.add(new OpenLot(lot.purchase, lot.remaining));
        result.sort(Comparator.comparing(l -> l.getPurchase().getDateTime()));
        return result;
    }

    private static void moveLots(Map<Portfolio, List<Lot>> byPortfolio, TransactionPair<?> pair,
                    PortfolioTransaction tx, Security security)
    {
        if (!(tx.getCrossEntry() instanceof PortfolioTransferEntry transfer))
        {
            // orphaned inbound transfer without a counterpart: treat the shares
            // as a fresh inbound delivery so they are not silently dropped
            byPortfolio.computeIfAbsent((Portfolio) pair.getOwner(), p -> new ArrayList<>())
                            .add(new Lot(tx, tx.getShares()));
            return;
        }

        Portfolio source = (Portfolio) transfer.getOwner(transfer.getSourceTransaction());
        Portfolio target = (Portfolio) transfer.getOwner(transfer.getTargetTransaction());

        List<Lot> targetLots = byPortfolio.computeIfAbsent(target, p -> new ArrayList<>());
        List<Lot> sourceLots = byPortfolio.computeIfAbsent(source, p -> new ArrayList<>());

        long leftover = take(sourceLots, tx.getShares(), targetLots);
        if (leftover > 0)
            PortfolioLog.warning(MessageFormat.format(
                            "Vorabpauschale: transfer of {0} shares exceeds holdings for security {1} on {2}",
                            Values.Share.format(leftover), security.getName(),
                            Values.DateTime.format(tx.getDateTime())));
    }

    /**
     * Removes up to {@code shares} from {@code from}, oldest lot first. Removed
     * fragments keep their original purchase transaction and are appended to
     * {@code into} when it is not null (a transfer), or discarded otherwise (a
     * sale). Returns the number of shares that could not be covered.
     */
    private static long take(List<Lot> from, long shares, List<Lot> into)
    {
        // re-establish FIFO by acquisition date; lots moved in by a transfer may
        // be older than lots already present in this portfolio
        from.sort(Comparator.comparing(l -> l.purchase.getDateTime()));

        long remaining = shares;
        var it = from.iterator();
        while (remaining > 0 && it.hasNext())
        {
            Lot lot = it.next();
            if (lot.remaining <= remaining)
            {
                remaining -= lot.remaining;
                if (into != null)
                    into.add(new Lot(lot.purchase, lot.remaining));
                it.remove();
            }
            else
            {
                if (into != null)
                    into.add(new Lot(lot.purchase, remaining));
                lot.remaining -= remaining;
                remaining = 0;
            }
        }
        return remaining;
    }
}
