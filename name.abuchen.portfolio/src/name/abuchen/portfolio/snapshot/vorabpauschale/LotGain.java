package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;

import name.abuchen.portfolio.money.Money;

/** One FIFO-consumed purchase lot within a sale, with its taxable gain. */
public final class LotGain
{
    private final LocalDate purchaseDate;
    private final long shares;
    private final Money proceeds;
    private final Money cost;
    private final Money accumulatedVorabpauschale;
    private final Money gainBeforeExemption;
    private final Money taxableGain;

    public LotGain(LocalDate purchaseDate, long shares, Money proceeds, Money cost,
                    Money accumulatedVorabpauschale, Money gainBeforeExemption, Money taxableGain)
    {
        this.purchaseDate = purchaseDate;
        this.shares = shares;
        this.proceeds = proceeds;
        this.cost = cost;
        this.accumulatedVorabpauschale = accumulatedVorabpauschale;
        this.gainBeforeExemption = gainBeforeExemption;
        this.taxableGain = taxableGain;
    }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public long getShares() { return shares; }
    public Money getProceeds() { return proceeds; }
    public Money getCost() { return cost; }
    public Money getAccumulatedVorabpauschale() { return accumulatedVorabpauschale; }
    public Money getGainBeforeExemption() { return gainBeforeExemption; }
    public Money getTaxableGain() { return taxableGain; }
}
