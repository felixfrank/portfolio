package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.money.Money;

/** All sales in the target year with the total taxable gain. */
public final class GermanTaxGainResult
{
    private final int year;
    private final List<SaleGain> sales;
    private final Money totalTaxableGain;
    private final List<String> warnings;

    public GermanTaxGainResult(int year, List<SaleGain> sales, Money totalTaxableGain, List<String> warnings)
    {
        this.year = year;
        this.sales = Collections.unmodifiableList(new ArrayList<>(sales));
        this.totalTaxableGain = totalTaxableGain;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public int getYear()
    {
        return year;
    }
    public List<SaleGain> getSales()
    {
        return sales;
    }
    public Money getTotalTaxableGain()
    {
        return totalTaxableGain;
    }
    public List<String> getWarnings()
    {
        return warnings;
    }
}
