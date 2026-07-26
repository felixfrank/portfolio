package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;

import name.abuchen.portfolio.money.Money;

/**
 * Computes the German withholding tax on capital income (Abgeltungsteuer plus
 * Solidaritätszuschlag and, optionally, Kirchensteuer) for a given taxable base.
 *
 * <p>
 * Per § 32d Abs. 1 EStG the Abgeltungsteuer is reduced when church tax applies,
 * because the church tax is itself deductible: the rate is {@code base / (4 + k)}
 * with {@code k} the church-tax rate (0.08 in Bavaria and Baden-Württemberg,
 * 0.09 elsewhere, 0 if not liable). Soli (5.5 %) and church tax (k) are then
 * levied on the assessed Abgeltungsteuer. The base may be negative (a loss), in
 * which case the resulting tax is negative (offsetting); note that real loss
 * relief runs through the Verlustverrechnungstöpfe, so this is a planning
 * estimate.
 */
public final class GermanCapitalTax
{
    /** Bavaria and Baden-Württemberg. */
    public static final BigDecimal CHURCH_RATE_8 = new BigDecimal("0.08");
    /** All other Bundesländer. */
    public static final BigDecimal CHURCH_RATE_9 = new BigDecimal("0.09");

    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal SOLI_RATE = new BigDecimal("0.055");

    private GermanCapitalTax()
    {
    }

    /** The tax owed on a taxable base, split into its components. */
    public static final class Breakdown
    {
        private final Money kapitalertragsteuer;
        private final Money soli;
        private final Money kirchensteuer;
        private final Money total;

        private Breakdown(Money kapitalertragsteuer, Money soli, Money kirchensteuer, Money total)
        {
            this.kapitalertragsteuer = kapitalertragsteuer;
            this.soli = soli;
            this.kirchensteuer = kirchensteuer;
            this.total = total;
        }

        public Money getKapitalertragsteuer()
        {
            return kapitalertragsteuer;
        }
        public Money getSoli()
        {
            return soli;
        }
        public Money getKirchensteuer()
        {
            return kirchensteuer;
        }
        public Money getTotal()
        {
            return total;
        }
    }

    public static Breakdown compute(Money taxableBase, BigDecimal churchRate)
    {
        var currency = taxableBase.getCurrencyCode();
        var k = churchRate == null ? BigDecimal.ZERO : churchRate;

        var base = BigDecimal.valueOf(taxableBase.getAmount());
        long kapest = base.divide(FOUR.add(k), 0, RoundingMode.HALF_UP).longValue();
        long soli = BigDecimal.valueOf(kapest).multiply(SOLI_RATE).setScale(0, RoundingMode.HALF_UP).longValue();
        long kirche = BigDecimal.valueOf(kapest).multiply(k).setScale(0, RoundingMode.HALF_UP).longValue();

        return new Breakdown(Money.of(currency, kapest), Money.of(currency, soli), Money.of(currency, kirche),
                        Money.of(currency, kapest + soli + kirche));
    }
}
