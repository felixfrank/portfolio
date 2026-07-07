package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.TransactionPair;
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

        var perShareStart = perShareInTermCurrency(security, yearStart, converter, warnings, "start");
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
        var perShareEnd = perShareInTermCurrency(security, yearEnd, converter, warnings, "end");
        if (perShareEnd != null)
        {
            var totalSharesDec = BigDecimal.valueOf(totalShares).movePointLeft(Values.Share.precision());
            var yearEndValueCents = BigDecimal.valueOf(perShareEnd.getAmount()).multiply(totalSharesDec);
            capCents = yearEndValueCents.subtract(yearStartValueCents).add(distributionsCents).max(BigDecimal.ZERO);
        }

        var cappedCents = capCents == null ? grossCents : grossCents.min(capCents);
        var vorabCents = cappedCents.subtract(distributionsCents).max(BigDecimal.ZERO);
        var taxableCents = vorabCents.multiply(teilfreistellungFactor);

        List<LotContribution> contributions = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++)
        {
            var share = grossCents.signum() == 0 ? BigDecimal.ZERO
                            : lotBasisertrag.get(i).divide(grossCents, 12, RoundingMode.HALF_UP);
            var contribution = money(eur, vorabCents.multiply(share));
            contributions.add(new LotContribution(lots.get(i).getPurchase().getDateTime().toLocalDate(),
                            lots.get(i).getShares(), money(eur, lotBaseValue.get(i)), lotTimeFactor.get(i),
                            money(eur, lotBasisertrag.get(i)), contribution));
        }

        return new VorabpauschaleResult(security, year, money(eur, yearStartValueCents),
                        money(eur, distributionsCents), money(eur, grossCents), money(eur, cappedCents),
                        money(eur, vorabCents), money(eur, taxableCents), contributions, warnings);
    }

    private static Money perShareInTermCurrency(Security security, LocalDate date, CurrencyConverter converter,
                    List<String> warnings, String which)
    {
        SecurityPrice price = security.getSecurityPrice(date);
        if (price == null || price.getValue() == 0)
        {
            warnings.add("Missing year-" + which + " price for " + security.getName());
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
