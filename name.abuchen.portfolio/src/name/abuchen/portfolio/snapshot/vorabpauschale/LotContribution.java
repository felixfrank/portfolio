package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.time.LocalDate;

import name.abuchen.portfolio.money.Money;

/** One purchase lot's contribution to a year's Vorabpauschale. */
public final class LotContribution
{
    private final LocalDate purchaseDate;
    private final long shares;
    private final Money baseValue;      // year-start NAV x shares, in EUR
    private final BigDecimal timeFactor; // 1, or (13 - purchaseMonth)/12
    private final Money basisertrag;    // baseValue x Basiszins x 0.70 x timeFactor
    private final Money contribution;   // this lot's share of the (post-cap, post-distribution) Vorabpauschale

    public LotContribution(LocalDate purchaseDate, long shares, Money baseValue, BigDecimal timeFactor,
                    Money basisertrag, Money contribution)
    {
        this.purchaseDate = purchaseDate;
        this.shares = shares;
        this.baseValue = baseValue;
        this.timeFactor = timeFactor;
        this.basisertrag = basisertrag;
        this.contribution = contribution;
    }

    public LocalDate getPurchaseDate()
    {
        return purchaseDate;
    }
    public long getShares()
    {
        return shares;
    }
    public Money getBaseValue()
    {
        return baseValue;
    }
    public BigDecimal getTimeFactor()
    {
        return timeFactor;
    }
    public Money getBasisertrag()
    {
        return basisertrag;
    }
    public Money getContribution()
    {
        return contribution;
    }
}
