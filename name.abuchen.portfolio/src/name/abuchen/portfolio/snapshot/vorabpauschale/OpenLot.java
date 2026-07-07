package name.abuchen.portfolio.snapshot.vorabpauschale;

import name.abuchen.portfolio.model.PortfolioTransaction;

/**
 * A surviving purchase lot as of a given date: the original purchase
 * transaction plus the number of shares (in {@link name.abuchen.portfolio.money.Values#Share}
 * precision) still held from it.
 */
public final class OpenLot
{
    private final PortfolioTransaction purchase;
    private final long shares;

    public OpenLot(PortfolioTransaction purchase, long shares)
    {
        this.purchase = purchase;
        this.shares = shares;
    }

    public PortfolioTransaction getPurchase()
    {
        return purchase;
    }

    public long getShares()
    {
        return shares;
    }
}
