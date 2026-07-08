package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.trades.TradeCollector;

/**
 * Computes the German taxable capital gain (proceeds − cost − accumulated
 * Vorabpauschale, after Teilfreistellung) for every sale in a target year,
 * via a forward FIFO simulation that accrues each finalized year's
 * Vorabpauschale onto the then-open lots. Pure computation; no persistence, no
 * UI. All money is in the converter's term currency (EUR).
 *
 * v1 conventions: proceeds and cost both use gross value (fees/taxes excluded);
 * Teilfreistellung is applied to the net gain, symmetrically to losses.
 */
public final class GermanTaxGainCalculator
{
    private static final class Lot
    {
        private final PortfolioTransaction purchase;
        private long remainingShares;
        private long costCents;         // for the remaining shares
        private long accumulatedCents;  // Vorabpauschale accrued onto remaining shares

        private Lot(PortfolioTransaction purchase, long costCents)
        {
            this.purchase = purchase;
            this.remainingShares = purchase.getShares();
            this.costCents = costCents;
        }
    }

    private GermanTaxGainCalculator()
    {
    }

    public static GermanTaxGainResult compute(Client client, int year,
                    Function<Security, BigDecimal> teilfreistellungResolver, CurrencyConverter converter)
    {
        var termCurrency = converter.getTermCurrency();
        List<SaleGain> sales = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long totalTaxable = 0;

        for (var security : client.getSecurities())
        {
            var exemption = teilfreistellungResolver.apply(security);
            var forSecurity = computeForSecurity(client, security, year, exemption, converter, warnings);
            sales.addAll(forSecurity);
            for (var s : forSecurity)
                totalTaxable += s.getTaxableGain().getAmount();
        }

        return new GermanTaxGainResult(year, sales, Money.of(termCurrency, totalTaxable), warnings);
    }

    private static List<SaleGain> computeForSecurity(Client client, Security security, int year,
                    BigDecimal exemption, CurrencyConverter converter, List<String> warnings)
    {
        var termCurrency = converter.getTermCurrency();
        var yearEnd = LocalDate.of(year, 12, 31);

        // sort all transactions for this security by date+type (the OpenLotsResolver idiom)
        List<TransactionPair<?>> all = security.getTransactions(client);
        Collections.sort(all, TradeCollector.BY_DATE_AND_TYPE);

        // collect portfolio transactions up to year end, preserving sorted order
        List<PortfolioTransaction> txs = new ArrayList<>();
        for (var pair : all)
        {
            if (pair.getTransaction() instanceof PortfolioTransaction tx
                            && !tx.getDateTime().toLocalDate().isAfter(yearEnd))
                txs.add(tx);
        }
        if (txs.isEmpty())
            return Collections.emptyList();

        // ledger vorabpauschale (cents) per year for this security; ledger
        // amounts are always stored in the client base currency (EUR), the same
        // as the converter's term currency, so no conversion is needed here
        Map<Integer, Long> ledgerByYear = new HashMap<>();
        for (var e : client.getVorabpauschaleEntries())
        {
            if (e.getSecurity() == security)
                ledgerByYear.put(e.getYear(), e.getVorabpauschale().getAmount());
        }

        Deque<Lot> open = new ArrayDeque<>();
        List<SaleGain> result = new ArrayList<>();

        // start applying year-ends from the year of the first transaction
        int nextYearEndToApply = txs.get(0).getDateTime().getYear();

        for (var tx : txs)
        {
            int txYear = tx.getDateTime().getYear();

            // apply year-end events for all past years before this transaction's year
            while (nextYearEndToApply < txYear && nextYearEndToApply < year)
            {
                applyYearEnd(open, nextYearEndToApply, ledgerByYear);
                nextYearEndToApply++;
            }

            switch (tx.getType())
            {
                case BUY, DELIVERY_INBOUND:
                    long costCents = converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue()).getAmount();
                    open.addLast(new Lot(tx, costCents));
                    break;
                case SELL, DELIVERY_OUTBOUND:
                    boolean inTargetYear = txYear == year;
                    long proceedsCents = converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue())
                                    .getAmount();
                    consumeSale(open, tx, proceedsCents, inTargetYear, security, exemption, termCurrency, result,
                                    warnings);
                    break;
                case TRANSFER_IN, TRANSFER_OUT:
                    // investor-level no-op; transfers between the investor's own
                    // portfolios do not change the holding
                    break;
                default:
                    throw new UnsupportedOperationException(tx.getType().name());
            }
        }

        return result;
    }

    private static void applyYearEnd(Deque<Lot> open, int yearZ, Map<Integer, Long> ledgerByYear)
    {
        var vorabCents = ledgerByYear.get(yearZ);
        if (vorabCents == null || open.isEmpty())
            return;

        // weight_i = remainingShares_i × timeFactor_i
        // timeFactor = 12 (full year) if purchased before yearZ, else 13 − purchaseMonth
        List<Lot> lots = new ArrayList<>(open);
        List<BigDecimal> weights = new ArrayList<>();
        var totalWeight = BigDecimal.ZERO;
        for (var lot : lots)
        {
            int months = lot.purchase.getDateTime().getYear() < yearZ ? 12
                            : 13 - lot.purchase.getDateTime().getMonthValue();
            var w = BigDecimal.valueOf(lot.remainingShares).multiply(BigDecimal.valueOf(months));
            weights.add(w);
            totalWeight = totalWeight.add(w);
        }
        if (totalWeight.signum() == 0)
            return;

        long allocated = 0;
        for (int i = 0; i < lots.size(); i++)
        {
            long share;
            if (i < lots.size() - 1)
            {
                share = BigDecimal.valueOf(vorabCents).multiply(weights.get(i))
                                .divide(totalWeight, 0, RoundingMode.HALF_UP).longValue();
                allocated += share;
            }
            else
            {
                // last lot takes the remainder so the total is exact
                share = vorabCents - allocated;
            }
            lots.get(i).accumulatedCents += share;
        }
    }

    private static void consumeSale(Deque<Lot> open, PortfolioTransaction sale, long proceedsCents,
                    boolean inTargetYear, Security security, BigDecimal exemption, String termCurrency,
                    List<SaleGain> result, List<String> warnings)
    {
        long sharesToSell = sale.getShares();
        long totalShares = sharesToSell;
        List<LotGain> lotGains = new ArrayList<>();

        while (sharesToSell > 0 && !open.isEmpty())
        {
            var lot = open.peekFirst();
            long take = Math.min(sharesToSell, lot.remainingShares);

            // fraction of this lot consumed
            long costTaken = fraction(lot.costCents, take, lot.remainingShares);
            long accumTaken = fraction(lot.accumulatedCents, take, lot.remainingShares);
            // fraction of the sale proceeds attributed to these shares; each lot
            // gets its own independently rounded share, so on multi-lot sales the
            // summed proceeds may differ from the transaction total by a cent
            long proceedsTaken = fraction(proceedsCents, take, totalShares);

            if (inTargetYear)
            {
                long gainBefore = proceedsTaken - costTaken - accumTaken;
                long taxable = BigDecimal.valueOf(gainBefore).multiply(exemption)
                                .setScale(0, RoundingMode.HALF_UP).longValue();
                lotGains.add(new LotGain(lot.purchase.getDateTime().toLocalDate(), take,
                                Money.of(termCurrency, proceedsTaken), Money.of(termCurrency, costTaken),
                                Money.of(termCurrency, accumTaken), Money.of(termCurrency, gainBefore),
                                Money.of(termCurrency, taxable)));
            }

            lot.costCents -= costTaken;
            lot.accumulatedCents -= accumTaken;
            lot.remainingShares -= take;
            sharesToSell -= take;
            if (lot.remainingShares == 0)
                open.removeFirst();
        }

        if (sharesToSell > 0)
            warnings.add("More shares sold than held for " + security.getName() + " on "
                            + Values.DateTime.format(sale.getDateTime()));

        if (inTargetYear && !lotGains.isEmpty())
        {
            long shares = totalShares - sharesToSell;
            long proceeds = sum(lotGains, LotGain::getProceeds);
            long cost = sum(lotGains, LotGain::getCost);
            long accum = sum(lotGains, LotGain::getAccumulatedVorabpauschale);
            long gainBefore = sum(lotGains, LotGain::getGainBeforeExemption);
            long taxable = sum(lotGains, LotGain::getTaxableGain);
            result.add(new SaleGain(security, sale.getDateTime().toLocalDate(), shares,
                            Money.of(termCurrency, proceeds), Money.of(termCurrency, cost),
                            Money.of(termCurrency, accum), Money.of(termCurrency, gainBefore),
                            Money.of(termCurrency, taxable), lotGains));
        }
    }

    private static long fraction(long total, long part, long whole)
    {
        if (whole == 0)
            return 0;
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
                        .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValue();
    }

    private static long sum(List<LotGain> lots, Function<LotGain, Money> field)
    {
        long s = 0;
        for (var lot : lots)
            s += field.apply(lot).getAmount();
        return s;
    }
}
