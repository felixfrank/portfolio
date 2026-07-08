package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;

/** One sale in the target year, with its FIFO-consumed lots and totals. */
public final class SaleGain
{
    private final Security security;
    private final LocalDate saleDate;
    private final long shares;
    private final Money proceeds;
    private final Money cost;
    private final Money accumulatedVorabpauschale;
    private final Money gainBeforeExemption;
    private final Money taxableGain;
    private final List<LotGain> lots;

    public SaleGain(Security security, LocalDate saleDate, long shares, Money proceeds, Money cost,
                    Money accumulatedVorabpauschale, Money gainBeforeExemption, Money taxableGain, List<LotGain> lots)
    {
        this.security = security;
        this.saleDate = saleDate;
        this.shares = shares;
        this.proceeds = proceeds;
        this.cost = cost;
        this.accumulatedVorabpauschale = accumulatedVorabpauschale;
        this.gainBeforeExemption = gainBeforeExemption;
        this.taxableGain = taxableGain;
        this.lots = Collections.unmodifiableList(new ArrayList<>(lots));
    }

    public Security getSecurity()
    {
        return security;
    }
    public LocalDate getSaleDate()
    {
        return saleDate;
    }
    public long getShares()
    {
        return shares;
    }
    public Money getProceeds()
    {
        return proceeds;
    }
    public Money getCost()
    {
        return cost;
    }
    public Money getAccumulatedVorabpauschale()
    {
        return accumulatedVorabpauschale;
    }
    public Money getGainBeforeExemption()
    {
        return gainBeforeExemption;
    }
    public Money getTaxableGain()
    {
        return taxableGain;
    }
    public List<LotGain> getLots()
    {
        return lots;
    }
}
