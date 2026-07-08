package name.abuchen.portfolio.model;

import java.math.BigDecimal;
import java.time.Instant;

import name.abuchen.portfolio.money.Money;

/**
 * A finalized Vorabpauschale (§ 18 InvStG) for one security in one calendar
 * year. Stored per (security, year); the per-lot breakdown is always derived on
 * demand and never persisted. All monetary values are in the same currency
 * (the client's base currency at the time it was finalized).
 */
public final class VorabpauschaleEntry
{
    private final Security security;
    private final int year;
    private final BigDecimal basiszins;
    private final BigDecimal teilfreistellungFactor;
    private final Money yearStartValue;
    private final Money distributions;
    private final Money cappedBasisertrag;
    private final Money vorabpauschale;
    private final Money taxable;
    private final Instant finalizedAt;

    public VorabpauschaleEntry(Security security, int year, BigDecimal basiszins, BigDecimal teilfreistellungFactor,
                    Money yearStartValue, Money distributions, Money cappedBasisertrag, Money vorabpauschale,
                    Money taxable, Instant finalizedAt)
    {
        this.security = security;
        this.year = year;
        this.basiszins = basiszins;
        this.teilfreistellungFactor = teilfreistellungFactor;
        this.yearStartValue = yearStartValue;
        this.distributions = distributions;
        this.cappedBasisertrag = cappedBasisertrag;
        this.vorabpauschale = vorabpauschale;
        this.taxable = taxable;
        this.finalizedAt = finalizedAt;
    }

    public Security getSecurity()
    {
        return security;
    }
    public int getYear()
    {
        return year;
    }
    public BigDecimal getBasiszins()
    {
        return basiszins;
    }
    public BigDecimal getTeilfreistellungFactor()
    {
        return teilfreistellungFactor;
    }
    public Money getYearStartValue()
    {
        return yearStartValue;
    }
    public Money getDistributions()
    {
        return distributions;
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
    public Instant getFinalizedAt()
    {
        return finalizedAt;
    }
}
