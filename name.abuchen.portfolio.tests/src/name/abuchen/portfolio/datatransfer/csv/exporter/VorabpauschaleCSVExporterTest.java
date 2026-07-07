package name.abuchen.portfolio.datatransfer.csv.exporter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.vorabpauschale.GermanTaxGainResult;
import name.abuchen.portfolio.snapshot.vorabpauschale.LotContribution;
import name.abuchen.portfolio.snapshot.vorabpauschale.LotGain;
import name.abuchen.portfolio.snapshot.vorabpauschale.SaleGain;
import name.abuchen.portfolio.snapshot.vorabpauschale.VorabpauschaleResult;

@SuppressWarnings("nls")
public class VorabpauschaleCSVExporterTest
{
    @Test
    public void testExportVorabpauschale() throws Exception
    {
        Security security = new Security();
        security.setName("TestFund");
        security.setIsin("IE00TEST0001");

        LotContribution lot = new LotContribution(LocalDate.of(2024, 3, 10), 100_00000000L,
                        Money.of("EUR", 10_000_00), new BigDecimal("0.8333333333"), Money.of("EUR", 147_58),
                        Money.of("EUR", 147_58));
        List<LotContribution> lots = new ArrayList<>();
        lots.add(lot);
        VorabpauschaleResult result = new VorabpauschaleResult(security, 2024, new BigDecimal("0.70"),
                        Money.of("EUR", 10_000_00), Money.of("EUR", 0), Money.of("EUR", 147_58),
                        Money.of("EUR", 147_58), Money.of("EUR", 147_58), Money.of("EUR", 103_31), lots,
                        new ArrayList<>());

        File file = File.createTempFile("vorab", ".csv");
        file.deleteOnExit();
        new CSVExporter().exportVorabpauschale(file, List.of(result));

        String content = Files.readString(file.toPath());
        // Assert only on locale-independent literals. Do NOT assert on formatted
        // amounts or the delimiter: CSVExporter.STRATEGY uses the locale list
        // separator and Values.Amount.format is locale-dependent.
        assertThat(content, containsString("Security"));      // header
        assertThat(content, containsString("Month bought"));  // header
        assertThat(content, containsString("TestFund"));
        assertThat(content, containsString("IE00TEST0001"));
    }

    @Test
    public void testExportGermanTaxGains() throws Exception
    {
        LotGain lot = new LotGain(LocalDate.of(2023, 6, 1), 100_00000000L, Money.of("EUR", 13_000_00),
                        Money.of("EUR", 10_000_00), Money.of("EUR", 177_10), Money.of("EUR", 2_822_90),
                        Money.of("EUR", 2_822_90));
        List<LotGain> lots = new ArrayList<>();
        lots.add(lot);
        SaleGain sale = new SaleGain(null, LocalDate.of(2025, 6, 1), 100_00000000L, Money.of("EUR", 13_000_00),
                        Money.of("EUR", 10_000_00), Money.of("EUR", 177_10), Money.of("EUR", 2_822_90),
                        Money.of("EUR", 2_822_90), lots);
        List<SaleGain> sales = new ArrayList<>();
        sales.add(sale);
        GermanTaxGainResult result = new GermanTaxGainResult(2025, sales, Money.of("EUR", 2_822_90),
                        new ArrayList<>());

        File file = File.createTempFile("gains", ".csv");
        file.deleteOnExit();
        new CSVExporter().exportGermanTaxGains(file, result, name -> "TestFund");

        String content = Files.readString(file.toPath());
        // locale-independent assertions only (see Task 3 note)
        assertThat(content, containsString("Sale date"));                  // header
        assertThat(content, containsString("Accumulated Vorabpauschale")); // header
        assertThat(content, containsString("TestFund"));
        assertThat(content, containsString("TOTAL"));                      // total row label
    }
}
