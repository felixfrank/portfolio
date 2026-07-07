package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

/**
 * Computes the German Vorabpauschale (§ 18 InvStG) for one security in one
 * calendar year, with a per-lot breakdown. Pure computation: no persistence,
 * no UI. All monetary results are in the converter's term currency (EUR).
 */
public final class VorabpauschaleCalculator
{
    private static final BigDecimal BASISERTRAG_FACTOR = new BigDecimal("0.70");

    private VorabpauschaleCalculator()
    {
    }

    public static VorabpauschaleResult compute(Client client, Security security, int year,
                    BigDecimal basiszinsPercent, BigDecimal teilfreistellungFactor, CurrencyConverter converter)
    {
        var eur = converter.getTermCurrency();
        var yearStart = LocalDate.of(year, 1, 1);
        var yearEnd = LocalDate.of(year, 12, 31);
        List<String> warnings = new ArrayList<>();

        var lots = OpenLotsResolver.resolve(client, security, yearEnd);

        long totalShares = lots.stream().mapToLong(OpenLot::getShares).sum();
        if (lots.isEmpty() || totalShares == 0)
            return zero(security, year, eur, warnings);

        var perShareStart = perShareInTermCurrency(security, yearStart, converter, warnings, true);
        if (perShareStart == null)
            return zero(security, year, eur, warnings);

        var basiszins = basiszinsPercent.movePointLeft(2); // 2.53 -> 0.0253

        List<BigDecimal> lotBasisertrag = new ArrayList<>();
        List<BigDecimal> lotBaseValue = new ArrayList<>();
        List<BigDecimal> lotTimeFactor = new ArrayList<>();
        var grossCents = BigDecimal.ZERO;
        var yearStartValueCents = BigDecimal.ZERO;

        for (var lot : lots)
        {
            var sharesDec = BigDecimal.valueOf(lot.getShares()).movePointLeft(Values.Share.precision());
            var baseValueCents = BigDecimal.valueOf(perShareStart.getAmount()).multiply(sharesDec);

            int pYear = lot.getPurchase().getDateTime().getYear();
            BigDecimal timeFactor;
            if (pYear < year)
            {
                timeFactor = BigDecimal.ONE;
            }
            else
            {
                int pMonth = lot.getPurchase().getDateTime().getMonthValue();
                timeFactor = BigDecimal.valueOf(13L - pMonth).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            }

            var basisertrag = baseValueCents.multiply(basiszins).multiply(BASISERTRAG_FACTOR)
                            .multiply(timeFactor);

            lotBaseValue.add(baseValueCents);
            lotTimeFactor.add(timeFactor);
            lotBasisertrag.add(basisertrag);
            grossCents = grossCents.add(basisertrag);
            yearStartValueCents = yearStartValueCents.add(baseValueCents);
        }

        var distributionsCents = BigDecimal.valueOf(distributions(client, security, year, converter).getAmount());

        BigDecimal capCents = null;
        var perShareEnd = perShareInTermCurrency(security, yearEnd, converter, warnings, false);
        if (perShareEnd != null)
        {
            var totalSharesDec = BigDecimal.valueOf(totalShares).movePointLeft(Values.Share.precision());
            var yearEndValueCents = BigDecimal.valueOf(perShareEnd.getAmount()).multiply(totalSharesDec);
            capCents = yearEndValueCents.subtract(yearStartValueCents).add(distributionsCents).max(BigDecimal.ZERO);
        }

        var cappedCents = capCents == null ? grossCents : grossCents.min(capCents);
        var vorabCents = cappedCents.subtract(distributionsCents).max(BigDecimal.ZERO);
        var taxableCents = vorabCents.multiply(teilfreistellungFactor);

        var vorabpauschale = money(eur, vorabCents);
        var contributions = buildContributions(eur, lots, lotBaseValue, lotTimeFactor, lotBasisertrag, grossCents,
                        vorabCents, vorabpauschale.getAmount());

        return new VorabpauschaleResult(security, year, money(eur, yearStartValueCents),
                        money(eur, distributionsCents), money(eur, grossCents), money(eur, cappedCents),
                        vorabpauschale, money(eur, taxableCents), contributions, warnings);
    }

    /**
     * Allocates the (post-cap, post-distribution) Vorabpauschale across the lots
     * proportionally to each lot's Basisertrag. To avoid a rounding drift, the
     * first N-1 lots are rounded independently and the last lot receives the
     * exact remainder so that the per-lot contributions always sum to
     * {@code vorabpauschaleAmount}.
     */
    private static List<LotContribution> buildContributions(String currency, List<OpenLot> lots,
                    List<BigDecimal> lotBaseValue, List<BigDecimal> lotTimeFactor, List<BigDecimal> lotBasisertrag,
                    BigDecimal grossCents, BigDecimal vorabCents, long vorabpauschaleAmount)
    {
        List<LotContribution> contributions = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < lots.size(); i++)
        {
            long contributionAmount;
            if (i < lots.size() - 1)
            {
                var share = grossCents.signum() == 0 ? BigDecimal.ZERO
                                : lotBasisertrag.get(i).divide(grossCents, 12, RoundingMode.HALF_UP);
                contributionAmount = money(currency, vorabCents.multiply(share)).getAmount();
                allocated += contributionAmount;
            }
            else
            {
                contributionAmount = vorabpauschaleAmount - allocated;
            }

            contributions.add(new LotContribution(lots.get(i).getPurchase().getDateTime().toLocalDate(),
                            lots.get(i).getShares(), money(currency, lotBaseValue.get(i)), lotTimeFactor.get(i),
                            money(currency, lotBasisertrag.get(i)), Money.of(currency, contributionAmount)));
        }
        return contributions;
    }

    private static Money perShareInTermCurrency(Security security, LocalDate date, CurrencyConverter converter,
                    List<String> warnings, boolean isYearStart)
    {
        // getSecurityPrice returns a 0-value price when no data exists
        SecurityPrice price = security.getSecurityPrice(date);
        if (price.getValue() == 0)
        {
            warnings.add(MessageFormat.format(
                            isYearStart ? "Missing year-start price for {0}" : "Missing year-end price for {0}",
                            security.getName()));
            return null;
        }
        long cents = BigDecimal.valueOf(price.getValue()).movePointLeft(Values.Quote.precisionDeltaToMoney())
                        .setScale(0, RoundingMode.HALF_UP).longValue();
        var inSecurityCcy = Money.of(security.getCurrencyCode(), cents);
        return converter.convert(date, inSecurityCcy);
    }

    private static Money distributions(Client client, Security security, int year, CurrencyConverter converter)
    {
        var eur = converter.getTermCurrency();
        long cents = 0;
        for (var pair : security.getTransactions(client))
        {
            if (pair.getTransaction() instanceof AccountTransaction tx //
                            && tx.getType() == AccountTransaction.Type.DIVIDENDS //
                            && tx.getDateTime().getYear() == year)
            {
                cents += converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue()).getAmount();
            }
        }
        return Money.of(eur, cents);
    }

    private static Money money(String currency, BigDecimal cents)
    {
        return Money.of(currency, cents.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static VorabpauschaleResult zero(Security security, int year, String eur, List<String> warnings)
    {
        var z = Money.of(eur, 0);
        return new VorabpauschaleResult(security, year, z, z, z, z, z, z, new ArrayList<>(), warnings);
    }
}
