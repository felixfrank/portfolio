package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;

/** Result of computing the Vorabpauschale for one security in one year. */
public final class VorabpauschaleResult
{
    private final Security security;
    private final int year;
    private final BigDecimal teilfreistellungFactor;
    private final Money perShareYearStart;
    private final Money yearStartValue;
    private final Money distributions;
    private final Money grossBasisertrag;
    private final Money cappedBasisertrag;
    private final Money vorabpauschale;
    private final Money taxable;
    private final List<LotContribution> lots;
    private final List<String> warnings;

    public VorabpauschaleResult(Security security, int year, BigDecimal teilfreistellungFactor, Money perShareYearStart,
                    Money yearStartValue, Money distributions, Money grossBasisertrag, Money cappedBasisertrag,
                    Money vorabpauschale, Money taxable, List<LotContribution> lots, List<String> warnings)
    {
        this.security = security;
        this.year = year;
        this.teilfreistellungFactor = teilfreistellungFactor;
        this.perShareYearStart = perShareYearStart;
        this.yearStartValue = yearStartValue;
        this.distributions = distributions;
        this.grossBasisertrag = grossBasisertrag;
        this.cappedBasisertrag = cappedBasisertrag;
        this.vorabpauschale = vorabpauschale;
        this.taxable = taxable;
        this.lots = Collections.unmodifiableList(new ArrayList<>(lots));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public Security getSecurity()
    {
        return security;
    }
    public int getYear()
    {
        return year;
    }
    public BigDecimal getTeilfreistellungFactor()
    {
        return teilfreistellungFactor;
    }
    public Money getPerShareYearStart()
    {
        return perShareYearStart;
    }
    public Money getYearStartValue()
    {
        return yearStartValue;
    }
    public Money getDistributions()
    {
        return distributions;
    }
    public Money getGrossBasisertrag()
    {
        return grossBasisertrag;
    }
    public Money getCappedBasisertrag()
    {
        return cappedBasisertrag;
    }
    public Money getVorabpauschale()
    {
        return vorabpauschale;
    }
    public Money getTaxable()
    {
        return taxable;
    }
    public List<LotContribution> getLots()
    {
        return lots;
    }
    public List<String> getWarnings()
    {
        return warnings;
    }
}
