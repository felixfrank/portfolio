# Vorabpauschale — Plan 4b: Taxes menu + dialogs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the tested backend into the UI: a new **Taxes** menu with two entries opening lean summary-table dialogs — a Vorabpauschale computation dialog (year + Basiszins, per-security summary, Export CSV, Finalize) and a German realized-gains report dialog (year, per-sale summary, Export CSV).

**Architecture:** Two JFace `Dialog`s (mirroring `ListSelectionDialog`) driven by the Plan 1/3 calculators; a small core helper resolves each security's Teilfreistellung factor from the attribute (Plan 4a). Two `@Execute` handlers open them, obtaining `Client` + `ExchangeRateProviderFactory` via `MenuHelper`. The Taxes menu is three Command/Handler/HandledMenuItem trios in `Application.e4xmi`. Finalize writes `VorabpauschaleEntry`s and calls `ClientInput.markDirty()`.

**⚠️ Verification is manual.** SWT dialogs cannot be unit-tested here. Each task verifies by COMPILING; the whole plan is verified by the human running Portfolio Performance and clicking through (Task 8 checklist). Only Task 1 (the factor resolver) has an automated test.

**Spec:** `docs/superpowers/specs/2026-07-07-vorabpauschale-design.md` (§4.4). **Depends on:** Plans 1–3 and 4a (committed).

---

## Reference APIs (verified against the codebase)
- Calculators: `VorabpauschaleCalculator.compute(client, year, BigDecimal basiszinsPercent, BigDecimal teilfreistellungFactor, converter)` → `VorabpauschaleResult` (getters: getSecurity, getYearStartValue, getDistributions, getGrossBasisertrag, getVorabpauschale, getTaxable, getLots, getWarnings, getTeilfreistellungFactor). `GermanTaxGainCalculator.compute(client, year, Function<Security,BigDecimal> resolver, converter)` → `GermanTaxGainResult` (getSales, getTotalTaxableGain, getWarnings; `SaleGain`: getSecurity, getSaleDate, getShares, getProceeds, getCost, getAccumulatedVorabpauschale, getTaxableGain).
- Ledger: `client.addVorabpauschaleEntry(new VorabpauschaleEntry(security, year, factor, yearStartValue, distributions, cappedBasisertrag, vorabpauschale, taxable, Instant.now()))`; `client.getVorabpauschaleEntries()`.
- CSV: `new CSVExporter().exportVorabpauschale(file, List<VorabpauschaleResult>, securityName, isin)`; `new CSVExporter().exportGermanTaxGains(file, GermanTaxGainResult, Function<Security,String>)`.
- Converter: `new CurrencyConverterImpl(exchangeRateProviderFactory, client.getBaseCurrency())`.
- Handler context: `MenuHelper.getActiveClientInput(part)` → `ClientInput` (`.getClient()`, `.getExchangeRateProviderFacory()`, `.markDirty()`). Handler pattern: see `ExportHandler`.
- Attribute: `client.getSettings().getAttributeTypes()` (Stream<AttributeType>); `AttributeType.getId()` == `"teilfreistellung"`; `security.getAttributes().get(attributeType)` returns the stored `Double` (a FRACTION, e.g. 0.30 for 30 %) or null.
- Dialog idiom: extend `org.eclipse.jface.dialogs.Dialog`; `GridLayoutFactory`/`GridDataFactory`; `TableColumnLayout` + `TableViewerColumn` + `ColumnWeightData`; `ArrayContentProvider.getInstance()`; per-column `ColumnLabelProvider`. See `name.abuchen.portfolio.ui/.../dialogs/ListSelectionDialog.java`.

---

## Task 1: Teilfreistellung factor resolver (core, unit-tested)

**Files:**
- Create `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/TeilfreistellungFactor.java`
- Test `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/TeilfreistellungFactorTest.java`

Semantics: the `teilfreistellung` attribute stores the EXEMPT fraction (0.30 = 30 % Teilfreistellung). The calculator wants the TAXABLE factor = `1 − exemption`. No attribute / null value → exemption 0 → factor `1.0`.

- [ ] **Step 1: Failing test**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class TeilfreistellungFactorTest
{
    @Test
    public void testDefaultIsOneWhenUnset()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        assertThat(TeilfreistellungFactor.of(client, security), comparesEqualTo(BigDecimal.ONE));
    }

    @Test
    public void testEquityEtfThirtyPercentExemptionGivesPointSeven()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        var type = client.getSettings().getAttributeTypes()
                        .filter(t -> "teilfreistellung".equals(t.getId())).findFirst().orElseThrow();
        security.getAttributes().put(type, Double.valueOf(0.30));

        assertThat(TeilfreistellungFactor.of(client, security), comparesEqualTo(new BigDecimal("0.70")));
    }
}
```

Confirm `security.getAttributes().put(AttributeType, Object)` exists (grep `Attributes.java`); if the setter differs, use the real one.

- [ ] **Step 2: Run — fails (class missing).**

- [ ] **Step 3: Implement**

```java
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
```

- [ ] **Step 4: Run — passes. Commit** "Add Teilfreistellung factor resolver".

---

## Task 2: UI Messages

**Files:**
- Modify `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/Messages.java`
- Modify `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/messages.properties`
- Modify `name.abuchen.portfolio.bootstrap/OSGI-INF/l10n/bundle.properties` (menu label — see NOTE)

- [ ] **Step 1** Add these fields to the UI `Messages.java` (alphabetical) and values to `messages.properties`:

```java
    public static String LabelVorabpauschale;
    public static String LabelVorabpauschaleBasiszins;
    public static String LabelVorabpauschaleYear;
    public static String LabelGermanTaxGains;
    public static String LabelTaxesMenu;
    public static String VorabpauschaleColumnSecurity;
    public static String VorabpauschaleColumnYearStartValue;
    public static String VorabpauschaleColumnDistributions;
    public static String VorabpauschaleColumnGrossBasisertrag;
    public static String VorabpauschaleColumnVorabpauschale;
    public static String VorabpauschaleColumnTaxable;
    public static String GainsColumnSecurity;
    public static String GainsColumnSaleDate;
    public static String GainsColumnProceeds;
    public static String GainsColumnCost;
    public static String GainsColumnAccumulated;
    public static String GainsColumnTaxableGain;
    public static String LabelRecompute;
    public static String LabelFinalizeYear;
```

```properties
LabelVorabpauschale = Vorabpauschale
LabelVorabpauschaleBasiszins = Base rate (Basiszins) in %
LabelVorabpauschaleYear = Year
LabelGermanTaxGains = Realized gains (German tax)
LabelTaxesMenu = Taxes
VorabpauschaleColumnSecurity = Security
VorabpauschaleColumnYearStartValue = Year-start value
VorabpauschaleColumnDistributions = Distributions
VorabpauschaleColumnGrossBasisertrag = Gross Basisertrag
VorabpauschaleColumnVorabpauschale = Vorabpauschale
VorabpauschaleColumnTaxable = Taxable
GainsColumnSecurity = Security
GainsColumnSaleDate = Sale date
GainsColumnProceeds = Proceeds
GainsColumnCost = Cost
GainsColumnAccumulated = Accumulated Vorabpauschale
GainsColumnTaxableGain = Taxable gain
LabelRecompute = Recompute
LabelFinalizeYear = Finalize year
```

NOTE on the menu label: `Application.e4xmi` menu labels use `%key` references resolved from the bootstrap bundle's `OSGI-INF/l10n/bundle.properties` (see existing `%command.tools.name`). Add `command.taxes.name = Taxes` and `command.vorabpauschale.name = Vorabpauschale…` and `command.germanTaxGains.name = Realized gains (German tax)…` to that bundle.properties. Inspect the file first: `grep -n "command.tools.name" name.abuchen.portfolio.bootstrap/OSGI-INF/l10n/bundle.properties` to confirm location/format.

- [ ] **Step 2: Commit** "Add UI messages for Vorabpauschale dialogs".

---

## Task 3: `VorabpauschaleDialog`

**Files:**
- Create `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/dialogs/vorabpauschale/VorabpauschaleDialog.java`

Behavior: year `Spinner`, Basiszins `Text`, a `Recompute` button, a summary `TableViewer` (one row per security that has holdings and a non-empty result), and dialog-area buttons `Export CSV…` and `Finalize year`. On Recompute: for every security, run `VorabpauschaleCalculator.compute(client, year, basiszins, TeilfreistellungFactor.of(client, security), converter)`; keep results whose `getLots()` is non-empty. Export CSV: for the selected row (or all), open `FileDialog`, call `exportVorabpauschale`. Finalize: for each result create a `VorabpauschaleEntry` (using its `getTeilfreistellungFactor()`, `Instant.now()`), `client.addVorabpauschaleEntry(...)`, then `markDirty` via the supplied `Runnable`.

- [ ] **Step 1: Implement** (constructor takes what it needs; the handler passes `client`, `converter`, and a `markDirty` `Runnable`):

```java
package name.abuchen.portfolio.ui.dialogs.vorabpauschale;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;

import name.abuchen.portfolio.datatransfer.csv.exporter.CSVExporter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.VorabpauschaleEntry;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.vorabpauschale.TeilfreistellungFactor;
import name.abuchen.portfolio.snapshot.vorabpauschale.VorabpauschaleCalculator;
import name.abuchen.portfolio.snapshot.vorabpauschale.VorabpauschaleResult;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.PortfolioPlugin;

public class VorabpauschaleDialog extends Dialog
{
    private final Client client;
    private final CurrencyConverter converter;
    private final Runnable markDirty;

    private Spinner yearSpinner;
    private Text basiszinsText;
    private TableViewer tableViewer;
    private final List<VorabpauschaleResult> results = new ArrayList<>();

    public VorabpauschaleDialog(Shell parentShell, Client client, CurrencyConverter converter, Runnable markDirty)
    {
        super(parentShell);
        this.client = client;
        this.converter = converter;
        this.markDirty = markDirty;
    }

    @Override
    protected void setShellStyle(int newShellStyle)
    {
        super.setShellStyle(newShellStyle | SWT.RESIZE);
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Control contents = super.createContents(parent);
        getShell().setText(Messages.LabelVorabpauschale);
        return contents;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite composite = (Composite) super.createDialogArea(parent);

        Composite container = new Composite(composite, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).hint(700, 400).applyTo(container);
        GridLayoutFactory.fillDefaults().numColumns(5).margins(10, 10).applyTo(container);

        Label yearLabel = new Label(container, SWT.NONE);
        yearLabel.setText(Messages.LabelVorabpauschaleYear);
        yearSpinner = new Spinner(container, SWT.BORDER);
        yearSpinner.setMinimum(2018);
        yearSpinner.setMaximum(LocalDate.now().getYear());
        yearSpinner.setSelection(LocalDate.now().getYear() - 1);

        Label rateLabel = new Label(container, SWT.NONE);
        rateLabel.setText(Messages.LabelVorabpauschaleBasiszins);
        basiszinsText = new Text(container, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(basiszinsText);

        Button recompute = new Button(container, SWT.PUSH);
        recompute.setText(Messages.LabelRecompute);
        recompute.addListener(SWT.Selection, e -> recompute());

        Composite tableArea = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().span(5, 1).grab(true, true).applyTo(tableArea);
        TableColumnLayout layout = new TableColumnLayout();
        tableArea.setLayout(layout);

        tableViewer = new TableViewer(tableArea, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        addColumn(layout, Messages.VorabpauschaleColumnSecurity, 200,
                        r -> r.getSecurity() != null ? r.getSecurity().getName() : "");
        addColumn(layout, Messages.VorabpauschaleColumnYearStartValue, 120,
                        r -> Values.Money.format(r.getYearStartValue()));
        addColumn(layout, Messages.VorabpauschaleColumnDistributions, 100,
                        r -> Values.Money.format(r.getDistributions()));
        addColumn(layout, Messages.VorabpauschaleColumnGrossBasisertrag, 120,
                        r -> Values.Money.format(r.getGrossBasisertrag()));
        addColumn(layout, Messages.VorabpauschaleColumnVorabpauschale, 120,
                        r -> Values.Money.format(r.getVorabpauschale()));
        addColumn(layout, Messages.VorabpauschaleColumnTaxable, 100, r -> Values.Money.format(r.getTaxable()));

        tableViewer.setInput(results);
        return composite;
    }

    private interface CellText
    {
        String apply(VorabpauschaleResult result);
    }

    private void addColumn(TableColumnLayout layout, String label, int weight, CellText text)
    {
        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.NONE);
        column.getColumn().setText(label);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return text.apply((VorabpauschaleResult) element);
            }
        });
        layout.setColumnData(column.getColumn(), new ColumnWeightData(weight));
    }

    private BigDecimal parseBasiszins()
    {
        try
        {
            return new BigDecimal(basiszinsText.getText().trim().replace(',', '.'));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private void recompute()
    {
        BigDecimal basiszins = parseBasiszins();
        if (basiszins == null)
        {
            MessageDialog.openError(getShell(), Messages.LabelVorabpauschale, Messages.LabelVorabpauschaleBasiszins);
            return;
        }
        int year = yearSpinner.getSelection();
        results.clear();
        for (Security security : client.getSecurities())
        {
            VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, year, basiszins,
                            TeilfreistellungFactor.of(client, security), converter);
            if (!r.getLots().isEmpty())
                results.add(r);
        }
        tableViewer.setInput(results);
        tableViewer.refresh();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, 2000, Messages.LabelFinalizeYear, false);
        createButton(parent, 2001, Messages.MenuExportData, false);
        super.createButtonsForButtonBar(parent); // OK / Cancel
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == 2000)
            finalizeYear();
        else if (buttonId == 2001)
            exportCsv();
        else
            super.buttonPressed(buttonId);
    }

    private void finalizeYear()
    {
        if (results.isEmpty())
            return;
        int year = yearSpinner.getSelection();
        for (VorabpauschaleResult r : results)
        {
            client.addVorabpauschaleEntry(new VorabpauschaleEntry(r.getSecurity(), year,
                            r.getTeilfreistellungFactor(), r.getYearStartValue(), r.getDistributions(),
                            r.getCappedBasisertrag(), r.getVorabpauschale(), r.getTaxable(), Instant.now()));
        }
        markDirty.run();
        MessageDialog.openInformation(getShell(), Messages.LabelVorabpauschale, Messages.LabelFinalizeYear);
    }

    private void exportCsv()
    {
        if (results.isEmpty())
            return;
        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setFileName("vorabpauschale-" + yearSpinner.getSelection() + ".csv"); //$NON-NLS-1$ //$NON-NLS-2$
        dialog.setFilterExtensions(new String[] { "*.csv" }); //$NON-NLS-1$
        String path = dialog.open();
        if (path == null)
            return;
        try
        {
            new CSVExporter().exportVorabpauschale(new File(path), results);
        }
        catch (IOException e)
        {
            PortfolioPlugin.log(e);
            MessageDialog.openError(getShell(), Messages.LabelVorabpauschale, e.getMessage());
        }
    }
}
```

IMPLEMENTER NOTES (verified — apply as stated):
- `Messages.MenuExportData` ("Export data as CSV") already exists in the UI messages — use it for the export button (done above). No new export-label key is needed; use `Messages.MenuExportData` in the gains dialog too.
- **Fix the `exportVorabpauschale` signature (touches Plan 4a).** The Plan 4a method is `exportVorabpauschale(File, List<VorabpauschaleResult>, String securityName, String isin)` — wrong for a multi-security export. In THIS task, change it to `exportVorabpauschale(File file, List<VorabpauschaleResult> results)` that derives the name/ISIN per row from `result.getSecurity()` (null-guarded: `result.getSecurity() != null ? result.getSecurity().getName() : ""` and same for `getIsin()`). Then update the Plan 4a test `testExportVorabpauschale` to build a REAL security instead of passing `null`: `Security security = new Security(); security.setName("TestFund"); security.setIsin("IE00TEST0001");` pass it as the `VorabpauschaleResult` 1st arg, and call `exportVorabpauschale(file, List.of(result))`. Keep the same locale-independent assertions. Re-run `VorabpauschaleCSVExporterTest`.
- Verified present: `Values.Money.format(Money)`, `PortfolioPlugin.log(Throwable)`, `MenuHelper.isClientPartActive(MPart)`, `Attributes.put(AttributeType, Object)`.

- [ ] **Step 2: Compile the UI module.** Commit "Add VorabpauschaleDialog".

Build/compile command for UI:
```bash
export MAVEN_OPTS="-Xmx4g"
mvn -f portfolio-app/pom.xml clean compile -Plocal-dev -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.bootstrap,:name.abuchen.portfolio.ui -am -amd
```

---

## Task 4: `VorabpauschaleHandler`

**Files:**
- Create `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/handlers/tools/VorabpauschaleHandler.java`

Mirror `ExportHandler`.

```java
package name.abuchen.portfolio.ui.handlers.tools;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.ui.dialogs.vorabpauschale.VorabpauschaleDialog;
import name.abuchen.portfolio.ui.handlers.MenuHelper;

public class VorabpauschaleHandler
{
    @CanExecute
    boolean isVisible(@Named(IServiceConstants.ACTIVE_PART) MPart part)
    {
        return MenuHelper.isClientPartActive(part);
    }

    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClientInput(part).ifPresent(input -> {
            var converter = new CurrencyConverterImpl(input.getExchangeRateProviderFacory(),
                            input.getClient().getBaseCurrency());
            new VorabpauschaleDialog(shell, input.getClient(), converter, input::markDirty).open();
        });
    }
}
```
Verify `MenuHelper.isClientPartActive(MPart)` exists (used by `ExportHandler`). Compile. Commit "Add VorabpauschaleHandler".

---

## Task 5: `GermanTaxGainsDialog`

**Files:**
- Create `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/dialogs/vorabpauschale/GermanTaxGainsDialog.java`

Same shape as `VorabpauschaleDialog` but: year `Spinner` + `Recompute` + summary `TableViewer` of `SaleGain`s + `Export CSV…` (no Basiszins, no Finalize). On recompute: `GermanTaxGainResult result = GermanTaxGainCalculator.compute(client, year, s -> TeilfreistellungFactor.of(client, s), converter); input = result.getSales();`. Export: `new CSVExporter().exportGermanTaxGains(file, result, Security::getName)`.

```java
package name.abuchen.portfolio.ui.dialogs.vorabpauschale;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;

import name.abuchen.portfolio.datatransfer.csv.exporter.CSVExporter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.vorabpauschale.GermanTaxGainCalculator;
import name.abuchen.portfolio.snapshot.vorabpauschale.GermanTaxGainResult;
import name.abuchen.portfolio.snapshot.vorabpauschale.SaleGain;
import name.abuchen.portfolio.snapshot.vorabpauschale.TeilfreistellungFactor;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.PortfolioPlugin;

public class GermanTaxGainsDialog extends Dialog
{
    private final Client client;
    private final CurrencyConverter converter;

    private Spinner yearSpinner;
    private TableViewer tableViewer;
    private GermanTaxGainResult result;

    public GermanTaxGainsDialog(Shell parentShell, Client client, CurrencyConverter converter)
    {
        super(parentShell);
        this.client = client;
        this.converter = converter;
    }

    @Override
    protected void setShellStyle(int newShellStyle)
    {
        super.setShellStyle(newShellStyle | SWT.RESIZE);
    }

    @Override
    protected Control createContents(Composite parent)
    {
        Control contents = super.createContents(parent);
        getShell().setText(Messages.LabelGermanTaxGains);
        return contents;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite composite = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(composite, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).hint(760, 400).applyTo(container);
        GridLayoutFactory.fillDefaults().numColumns(3).margins(10, 10).applyTo(container);

        Label yearLabel = new Label(container, SWT.NONE);
        yearLabel.setText(Messages.LabelVorabpauschaleYear);
        yearSpinner = new Spinner(container, SWT.BORDER);
        yearSpinner.setMinimum(2018);
        yearSpinner.setMaximum(LocalDate.now().getYear());
        yearSpinner.setSelection(LocalDate.now().getYear() - 1);

        Button recompute = new Button(container, SWT.PUSH);
        recompute.setText(Messages.LabelRecompute);
        recompute.addListener(SWT.Selection, e -> recompute());

        Composite tableArea = new Composite(container, SWT.NONE);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, true).applyTo(tableArea);
        TableColumnLayout layout = new TableColumnLayout();
        tableArea.setLayout(layout);

        tableViewer = new TableViewer(tableArea, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        addColumn(layout, Messages.GainsColumnSecurity, 200,
                        s -> s.getSecurity() != null ? s.getSecurity().getName() : "");
        addColumn(layout, Messages.GainsColumnSaleDate, 90, s -> s.getSaleDate().toString());
        addColumn(layout, Messages.GainsColumnProceeds, 100, s -> Values.Money.format(s.getProceeds()));
        addColumn(layout, Messages.GainsColumnCost, 100, s -> Values.Money.format(s.getCost()));
        addColumn(layout, Messages.GainsColumnAccumulated, 140,
                        s -> Values.Money.format(s.getAccumulatedVorabpauschale()));
        addColumn(layout, Messages.GainsColumnTaxableGain, 110, s -> Values.Money.format(s.getTaxableGain()));

        return composite;
    }

    private interface CellText
    {
        String apply(SaleGain sale);
    }

    private void addColumn(TableColumnLayout layout, String label, int weight, CellText text)
    {
        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.NONE);
        column.getColumn().setText(label);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return text.apply((SaleGain) element);
            }
        });
        layout.setColumnData(column.getColumn(), new ColumnWeightData(weight));
    }

    private void recompute()
    {
        int year = yearSpinner.getSelection();
        result = GermanTaxGainCalculator.compute(client, year, s -> TeilfreistellungFactor.of(client, s), converter);
        tableViewer.setInput(result.getSales());
        tableViewer.refresh();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, 2001, Messages.MenuExportData, false);
        super.createButtonsForButtonBar(parent);
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == 2001)
            exportCsv();
        else
            super.buttonPressed(buttonId);
    }

    private void exportCsv()
    {
        if (result == null || result.getSales().isEmpty())
            return;
        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        dialog.setFileName("german-tax-gains-" + yearSpinner.getSelection() + ".csv"); //$NON-NLS-1$ //$NON-NLS-2$
        dialog.setFilterExtensions(new String[] { "*.csv" }); //$NON-NLS-1$
        String path = dialog.open();
        if (path == null)
            return;
        try
        {
            new CSVExporter().exportGermanTaxGains(new File(path), result, Security::getName);
        }
        catch (IOException e)
        {
            PortfolioPlugin.log(e);
            MessageDialog.openError(getShell(), Messages.LabelGermanTaxGains, e.getMessage());
        }
    }
}
```
NOTE: uses the existing `Messages.MenuExportData`. Compile. Commit "Add GermanTaxGainsDialog".

---

## Task 6: `GermanTaxGainsHandler`

**Files:**
- Create `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/handlers/tools/GermanTaxGainsHandler.java`

Identical to `VorabpauschaleHandler` but opens `GermanTaxGainsDialog` (no markDirty needed). Compile. Commit "Add GermanTaxGainsHandler".

---

## Task 7: Taxes menu in `Application.e4xmi`

**Files:**
- Modify `name.abuchen.portfolio.bootstrap/Application.e4xmi`

Add, mirroring the existing `_BackfillExDates*` trio (menu item line ~55, handler line ~190, command line ~338):

- [ ] **Step 1: A new Taxes `menu:Menu`** as a child of the main menu bar (sibling of the Tools menu, whose id is `name.abuchen.portfolio.bootstrap.menu.tools`). Insert after the Tools `</children>` close:

```xml
        <children xsi:type="menu:Menu" xmi:id="_TaxesMenu" elementId="name.abuchen.portfolio.ui.menu.taxes" label="%command.taxes.name">
          <children xsi:type="menu:HandledMenuItem" xmi:id="_VorabpauschaleMenuItem" elementId="name.abuchen.portfolio.ui.menu.taxes.vorabpauschale" label="%command.vorabpauschale.name" command="_VorabpauschaleCommand"/>
          <children xsi:type="menu:HandledMenuItem" xmi:id="_GermanTaxGainsMenuItem" elementId="name.abuchen.portfolio.ui.menu.taxes.germanTaxGains" label="%command.germanTaxGains.name" command="_GermanTaxGainsCommand"/>
        </children>
```

- [ ] **Step 2: Two `<handlers>`** (near the other `<handlers ...>` at ~line 190):

```xml
  <handlers xmi:id="_VorabpauschaleHandler" elementId="name.abuchen.portfolio.ui.handler.taxes.vorabpauschale" contributionURI="bundleclass://name.abuchen.portfolio.ui/name.abuchen.portfolio.ui.handlers.tools.VorabpauschaleHandler" command="_VorabpauschaleCommand"/>
  <handlers xmi:id="_GermanTaxGainsHandler" elementId="name.abuchen.portfolio.ui.handler.taxes.germanTaxGains" contributionURI="bundleclass://name.abuchen.portfolio.ui/name.abuchen.portfolio.ui.handlers.tools.GermanTaxGainsHandler" command="_GermanTaxGainsCommand"/>
```

- [ ] **Step 3: Two `<commands>`** (near the other `<commands ...>` at ~line 338):

```xml
  <commands xmi:id="_VorabpauschaleCommand" elementId="name.abuchen.portfolio.ui.command.taxes.vorabpauschale" commandName="%command.vorabpauschale.name"/>
  <commands xmi:id="_GermanTaxGainsCommand" elementId="name.abuchen.portfolio.ui.command.taxes.germanTaxGains" commandName="%command.germanTaxGains.name"/>
```

Read the exact surrounding XML first and place these consistently. The `%command.*` labels resolve from the bootstrap `bundle.properties` (Task 2 NOTE).

- [ ] **Step 4: Full UI build** to confirm the model + handlers wire up:
```bash
export MAVEN_OPTS="-Xmx4g"
mvn -f portfolio-app/pom.xml clean verify -Plocal-dev \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.bootstrap,:name.abuchen.portfolio.ui,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.ui.tests -am -amd
```
Expected: BUILD SUCCESS. Commit "Add Taxes menu with Vorabpauschale and gains dialogs".

---

## Task 8: Manual verification (human)

The build passing only proves it compiles. The human must run PP and confirm:

- [ ] Taxes menu appears in the menu bar with two items.
- [ ] Vorabpauschale…: opens; entering a year + Basiszins (e.g. 2.53) and Recompute fills the table for securities with holdings; Export CSV writes a file; Finalize year adds entries (reopen shows they persist after save) and marks the file dirty.
- [ ] Realized gains…: opens; Recompute for a year with sales fills the table (taxable gains net of accumulated Vorabpauschale); Export CSV writes a file.
- [ ] Set a security's Teilfreistellung attribute to 30 % and confirm its taxable figures drop to 70 %.

Use `/run` or launch Portfolio Performance to perform these checks.

---

## Self-review checklist (done while writing)

- **Spec coverage (§4.4):** Taxes menu (Task 7) ✓; Vorabpauschale dialog with year+Basiszins+summary+CSV+Finalize (Task 3) ✓; gains dialog with summary+CSV (Task 5) ✓; Teilfreistellung from attribute (Task 1) ✓; markDirty on finalize (Task 3/4) ✓.
- **KNOWN ISSUE to fix during Task 3:** `exportVorabpauschale`'s single name/isin params don't fit a multi-security export — Task 3 note directs changing it to a `Function<Security,String>` resolver (like `exportGermanTaxGains`) and updating the Plan 4a test. Do this as part of Task 3.
- **Unverified-at-authoring API names flagged inline** for the implementer to confirm: `Values.Money.format`, `Messages.MenuExportData`/`LabelExportCsv`, `PortfolioPlugin.log`, `MenuHelper.isClientPartActive`, `Attributes.put`, bootstrap `bundle.properties` menu-label mechanism. Each note says to confirm and adjust to the real API.
- **Only Task 1 is auto-tested; Tasks 3–7 are compile-only + Task 8 manual.**
