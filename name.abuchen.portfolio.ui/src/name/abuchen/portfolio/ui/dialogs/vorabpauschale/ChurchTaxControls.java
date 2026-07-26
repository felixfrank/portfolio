package name.abuchen.portfolio.ui.dialogs.vorabpauschale;

import java.math.BigDecimal;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.vorabpauschale.GermanCapitalTax;
import name.abuchen.portfolio.ui.Messages;

/**
 * Shared church-tax (Kirchensteuer) dropdown and tax formatting for the
 * Vorabpauschale and realized-gains dialogs. The rate is a display/export
 * choice only and is not persisted.
 */
final class ChurchTaxControls
{
    private ChurchTaxControls()
    {
    }

    /** A read-only dropdown with None / 8 % / 9 %, defaulting to None. */
    static Combo createCombo(Composite parent)
    {
        Combo combo = new Combo(parent, SWT.READ_ONLY);
        combo.setItems(Messages.LabelChurchTaxNone, Messages.LabelChurchTax8, Messages.LabelChurchTax9);
        combo.select(0);
        return combo;
    }

    /** The church-tax rate for the current selection (0, 0.08 or 0.09). */
    static BigDecimal rate(Combo combo)
    {
        switch (combo.getSelectionIndex())
        {
            case 1:
                return GermanCapitalTax.CHURCH_RATE_8;
            case 2:
                return GermanCapitalTax.CHURCH_RATE_9;
            default:
                return BigDecimal.ZERO;
        }
    }

    /** The total tax owed on a taxable base, formatted for a table cell. */
    static String totalTax(Money taxableBase, BigDecimal rate)
    {
        return Values.Money.format(GermanCapitalTax.compute(taxableBase, rate).getTotal());
    }

    /** The Abgeltungsteuer / Soli / Kirchensteuer split, for a cell tooltip. */
    static String breakdownTooltip(Money taxableBase, BigDecimal rate)
    {
        var tax = GermanCapitalTax.compute(taxableBase, rate);
        return "Abgeltungsteuer " + Values.Money.format(tax.getKapitalertragsteuer()) //
                        + "\nSoli " + Values.Money.format(tax.getSoli()) //
                        + "\nKirchensteuer " + Values.Money.format(tax.getKirchensteuer());
    }
}
