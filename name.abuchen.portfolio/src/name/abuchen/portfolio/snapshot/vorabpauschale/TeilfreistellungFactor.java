package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

/**
 * Resolves a security's Teilfreistellung factor — the TAXABLE fraction of
 * investment income — from the {@code teilfreistellung} security attribute,
 * which stores the EXEMPT fraction (0.30 = 30 % partial exemption). Returns
 * {@code 1.0} when the attribute is unset.
 */
public final class TeilfreistellungFactor
{
    private static final String ATTRIBUTE_ID = "teilfreistellung"; //$NON-NLS-1$

    private TeilfreistellungFactor()
    {
    }

    public static BigDecimal of(Client client, Security security)
    {
        return client.getSettings().getAttributeTypes() //
                        .filter(t -> ATTRIBUTE_ID.equals(t.getId())) //
                        .findFirst() //
                        .map(security.getAttributes()::get) //
                        .filter(Double.class::isInstance) //
                        .map(v -> BigDecimal.ONE.subtract(BigDecimal.valueOf((Double) v))) //
                        .orElse(BigDecimal.ONE);
    }
}
