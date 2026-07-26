package name.abuchen.portfolio.ui.dialogs.vorabpauschale;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.LocalDate;
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

        Label finalizedLabel = new Label(container, SWT.NONE);
        GridDataFactory.fillDefaults().span(3, 1).grab(true, false).applyTo(finalizedLabel);
        SortedSet<Integer> finalizedYears = new TreeSet<>();
        for (var e : client.getVorabpauschaleEntries())
            finalizedYears.add(e.getYear());
        if (finalizedYears.isEmpty())
            finalizedLabel.setText(Messages.LabelVorabpauschaleFinalizedNone);
        else
            finalizedLabel.setText(MessageFormat.format(Messages.LabelVorabpauschaleFinalizedYears,
                            finalizedYears.stream().map(String::valueOf).collect(Collectors.joining(", "))));

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
        addColumn(layout, Messages.ColumnAccount, 120, s -> s.getAccount() != null ? s.getAccount().getName() : "");
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
        createButton(parent, 2002, Messages.LabelExportGermanTaxGainsByAccount, false);
        super.createButtonsForButtonBar(parent);
    }

    @Override
    protected void buttonPressed(int buttonId)
    {
        if (buttonId == 2001)
            exportCsv(false);
        else if (buttonId == 2002)
            exportCsv(true);
        else
            super.buttonPressed(buttonId);
    }

    private void exportCsv(boolean byAccount)
    {
        if (result == null || result.getSales().isEmpty())
            return;
        FileDialog dialog = new FileDialog(getShell(), SWT.SAVE);
        String prefix = byAccount ? "german-tax-gains-by-account-" : "german-tax-gains-"; //$NON-NLS-1$ //$NON-NLS-2$
        dialog.setFileName(prefix + yearSpinner.getSelection() + ".csv"); //$NON-NLS-1$
        dialog.setFilterExtensions(new String[] { "*.csv" }); //$NON-NLS-1$
        String path = dialog.open();
        if (path == null)
            return;
        try
        {
            if (byAccount)
                new CSVExporter().exportGermanTaxGainsByAccount(new File(path), result, Security::getName);
            else
                new CSVExporter().exportGermanTaxGains(new File(path), result, Security::getName);
        }
        catch (IOException e)
        {
            PortfolioPlugin.log(e);
            MessageDialog.openError(getShell(), Messages.LabelGermanTaxGains, e.getMessage());
        }
    }
}
