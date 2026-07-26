package name.abuchen.portfolio.ui.dialogs.vorabpauschale;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
import name.abuchen.portfolio.money.Money;
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
    private Label finalizedLabel;
    private TableViewer tableViewer;
    private final List<VorabpauschaleResult> results = new ArrayList<>();
    private BigDecimal lastBasiszins;

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

        finalizedLabel = new Label(container, SWT.NONE);
        GridDataFactory.fillDefaults().span(5, 1).grab(true, false).applyTo(finalizedLabel);
        refreshFinalizedLabel();

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
        lastBasiszins = basiszins;
        int year = yearSpinner.getSelection();
        results.clear();
        for (Security security : client.getSecurities())
        {
            VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, year, basiszins,
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

        List<VorabpauschaleEntry> existing = client.getVorabpauschaleEntries().stream()
                        .filter(e -> e.getYear() == year).collect(Collectors.toList());
        if (!existing.isEmpty())
        {
            boolean replace = MessageDialog.openConfirm(getShell(), Messages.LabelVorabpauschale,
                            MessageFormat.format(Messages.LabelVorabpauschaleReplaceConfirm, year, existing.size()));
            if (!replace)
                return;
            existing.forEach(client::removeVorabpauschaleEntry);
        }

        BigDecimal basiszins = lastBasiszins != null ? lastBasiszins : BigDecimal.ZERO;
        for (VorabpauschaleResult r : results)
        {
            client.addVorabpauschaleEntry(new VorabpauschaleEntry(r.getSecurity(), year, basiszins,
                            r.getTeilfreistellungFactor(), r.getYearStartValue(), r.getDistributions(),
                            r.getCappedBasisertrag(), r.getVorabpauschale(), r.getTaxable(), Instant.now()));
        }
        markDirty.run();
        refreshFinalizedLabel();
        MessageDialog.openInformation(getShell(), Messages.LabelVorabpauschale, Messages.LabelFinalizeYear);
    }

    private void refreshFinalizedLabel()
    {
        List<VorabpauschaleEntry> entries = client.getVorabpauschaleEntries();
        SortedSet<Integer> years = new TreeSet<>();
        for (VorabpauschaleEntry e : entries)
            years.add(e.getYear());

        if (years.isEmpty())
        {
            finalizedLabel.setText(Messages.LabelVorabpauschaleFinalizedNone);
            finalizedLabel.setToolTipText(null);
        }
        else
        {
            String list = years.stream().map(String::valueOf).collect(Collectors.joining(", "));
            finalizedLabel.setText(MessageFormat.format(Messages.LabelVorabpauschaleFinalizedYears, list));
            finalizedLabel.setToolTipText(buildFinalizedTooltip(entries, years));
        }
        finalizedLabel.requestLayout();
    }

    private String buildFinalizedTooltip(List<VorabpauschaleEntry> entries, SortedSet<Integer> years)
    {
        StringBuilder sb = new StringBuilder();
        for (Integer year : years)
        {
            Money vorab = null;
            Money taxable = null;
            BigDecimal basiszins = null;
            Instant finalizedAt = null;
            for (VorabpauschaleEntry e : entries)
            {
                if (e.getYear() != year)
                    continue;
                vorab = vorab == null ? e.getVorabpauschale() : vorab.add(e.getVorabpauschale());
                taxable = taxable == null ? e.getTaxable() : taxable.add(e.getTaxable());
                basiszins = e.getBasiszins();
                if (finalizedAt == null || e.getFinalizedAt().isAfter(finalizedAt))
                    finalizedAt = e.getFinalizedAt();
            }
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(year).append(" — ").append(Messages.VorabpauschaleColumnVorabpauschale).append(' ')
                            .append(Values.Money.format(vorab)).append(", ").append(Messages.VorabpauschaleColumnTaxable)
                            .append(' ').append(Values.Money.format(taxable)).append(", ")
                            .append(Messages.LabelVorabpauschaleBasiszins).append(' ').append(basiszins)
                            .append(", ").append(Values.DateTime.format(
                                            LocalDateTime.ofInstant(finalizedAt, ZoneId.systemDefault())));
        }
        return sb.toString();
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
