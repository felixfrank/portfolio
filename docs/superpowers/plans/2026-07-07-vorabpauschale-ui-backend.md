# Vorabpauschale — Plan 4a: Teilfreistellung attribute + CSV exporters

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The testable backend half of the UI: register the default **Teilfreistellung** security attribute, expose the exemption factor on `VorabpauschaleResult`, and add two `CSVExporter` methods (`exportVorabpauschale`, `exportGermanTaxGains`) — all unit-tested. Plan 4b then adds the Taxes menu and the two SWT dialogs that call these.

**Architecture:** `Teilfreistellung` is registered like the existing `ter` percent attribute (`Double.class` + `PercentConverter`, target `Security`). `VorabpauschaleResult` carries the exemption factor so the CSV can show per-lot taxable amounts. The two exporters follow the existing `CSVExporter` idiom (`CSVPrinter`, `Values.Amount.format`, `escapeNull`).

**Tech Stack:** Java 21, JUnit 4, Apache Commons CSV, PP `Values`/`Money`/`AttributeType`.

**Spec:** `docs/superpowers/specs/2026-07-07-vorabpauschale-design.md` (§4.3, §4.4).

**Depends on:** Plans 1–3 (committed). Plan 4b (dialogs/menu) depends on this.

---

## File structure

- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleResult.java` (add exemption factor)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculator.java` (pass the factor into the result)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientSettings.java` (register the attribute)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/Messages.java` + `messages.properties` (two keys)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/csv/exporter/CSVExporter.java` (two export methods)
- Test `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/datatransfer/csv/exporter/VorabpauschaleCSVExporterTest.java`

Compile/test:
```bash
export MAVEN_OPTS="-Xmx4g"
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=VorabpauschaleCSVExporterTest,VorabpauschaleCalculatorTest,GermanTaxGainCalculatorTest
```

---

## Task 1: Expose the Teilfreistellung factor on `VorabpauschaleResult`

**Files:**
- Modify `VorabpauschaleResult.java`
- Modify `VorabpauschaleCalculator.java`

- [ ] **Step 1: Add the field + getter to `VorabpauschaleResult`.** Add `private final BigDecimal teilfreistellungFactor;` (add `import java.math.BigDecimal;`), a constructor parameter placed immediately AFTER `int year`, assignment, and:

```java
    public BigDecimal getTeilfreistellungFactor() { return teilfreistellungFactor; }
```

The constructor becomes:
```java
    public VorabpauschaleResult(Security security, int year, BigDecimal teilfreistellungFactor, Money yearStartValue,
                    Money distributions, Money grossBasisertrag, Money cappedBasisertrag, Money vorabpauschale,
                    Money taxable, List<LotContribution> lots, List<String> warnings)
```
(Insert `this.teilfreistellungFactor = teilfreistellungFactor;` in the body.)

- [ ] **Step 2: Pass it from `VorabpauschaleCalculator`.** There are TWO `new VorabpauschaleResult(...)` call sites: the main return and the `zero(...)` helper. Update BOTH to pass `teilfreistellungFactor` as the new 3rd argument. The `zero(...)` helper must take the factor too — change its signature to `zero(Security security, int year, BigDecimal teilfreistellungFactor, String eur, List<String> warnings)` and pass it at both `zero(...)` call sites (the empty-lots case and the missing-price case both currently call `zero(security, year, eur, warnings)`).

- [ ] **Step 3: Run the existing calculator tests** to confirm nothing broke:
```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd -Dtest=VorabpauschaleCalculatorTest
```
Expected: the 8 existing tests still pass (they don't assert on the new getter, so they compile & pass unchanged).

- [ ] **Step 4: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleResult.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculator.java
git commit -m "Expose Teilfreistellung factor on VorabpauschaleResult"
```

---

## Task 2: Register the Teilfreistellung security attribute

**Files:**
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/Messages.java`
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/messages.properties`
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientSettings.java`

- [ ] **Step 1: Add two Messages keys.** In `Messages.java`, next to `AttributesTERName`/`AttributesTERColumn` (alphabetical), add:
```java
    public static String AttributesTeilfreistellungColumn;
    public static String AttributesTeilfreistellungName;
```
In `messages.properties`, next to the `AttributesTER*` entries, add:
```properties
AttributesTeilfreistellungColumn = Partial exemption
AttributesTeilfreistellungName = Teilfreistellung (partial exemption)
```

- [ ] **Step 2: Register the attribute** in `ClientSettings`, immediately after the `ter` block (mirror it exactly — it is a percent stored as `Double`):
```java
        AttributeType teilfreistellung = new AttributeType("teilfreistellung"); //$NON-NLS-1$
        teilfreistellung.setName(Messages.AttributesTeilfreistellungName);
        teilfreistellung.setColumnLabel(Messages.AttributesTeilfreistellungColumn);
        teilfreistellung.setTarget(Security.class);
        teilfreistellung.setType(Double.class);
        teilfreistellung.setConverter(PercentConverter.class);
        attributeTypes.add(teilfreistellung);
```
Verify `PercentConverter` and `AttributeType` are already imported in `ClientSettings` (the `ter` block uses them, so they are).

- [ ] **Step 3: Compile core**
```bash
mvn -f portfolio-app/pom.xml clean compile -Plocal-dev -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio -am -amd
```
Expected BUILD SUCCESS.

- [ ] **Step 4: Commit**
```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/Messages.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/messages.properties \
        name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientSettings.java
git commit -m "Register default Teilfreistellung security attribute"
```

---

## Task 3: `CSVExporter.exportVorabpauschale`

**Files:**
- Modify `CSVExporter.java`
- Test `VorabpauschaleCSVExporterTest.java`

One row per lot contribution (so "month bought" is visible), plus the per-security taxable derived from the exemption factor.

- [ ] **Step 1: Write the failing test** (create `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/datatransfer/csv/exporter/VorabpauschaleCSVExporterTest.java`):

```java
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

import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.snapshot.vorabpauschale.LotContribution;
import name.abuchen.portfolio.snapshot.vorabpauschale.VorabpauschaleResult;

@SuppressWarnings("nls")
public class VorabpauschaleCSVExporterTest
{
    @Test
    public void testExportVorabpauschale() throws Exception
    {
        LotContribution lot = new LotContribution(LocalDate.of(2024, 3, 10), 100_00000000L,
                        Money.of("EUR", 10_000_00), new BigDecimal("0.8333333333"), Money.of("EUR", 147_58),
                        Money.of("EUR", 147_58));
        List<LotContribution> lots = new ArrayList<>();
        lots.add(lot);
        VorabpauschaleResult result = new VorabpauschaleResult(null, 2024, new BigDecimal("0.70"),
                        Money.of("EUR", 10_000_00), Money.of("EUR", 0), Money.of("EUR", 147_58),
                        Money.of("EUR", 147_58), Money.of("EUR", 147_58), Money.of("EUR", 103_31), lots,
                        new ArrayList<>());

        File file = File.createTempFile("vorab", ".csv");
        file.deleteOnExit();
        new CSVExporter().exportVorabpauschale(file, List.of(result), "TestFund", "IE00TEST0001");

        String content = Files.readString(file.toPath());
        // Assert only on locale-independent literals. Do NOT assert on formatted
        // amounts or the delimiter: CSVExporter.STRATEGY uses the locale list
        // separator and Values.Amount.format is locale-dependent.
        assertThat(content, containsString("Security"));      // header
        assertThat(content, containsString("Month bought"));  // header
        assertThat(content, containsString("TestFund"));
        assertThat(content, containsString("IE00TEST0001"));
    }
}
```

NOTE: `VorabpauschaleResult`'s `security` is a `Security` model object; to keep the test free of model construction we pass `null` and let `exportVorabpauschale` take the display name + ISIN as explicit parameters (see signature below). This keeps the exporter decoupled from how the caller resolves security identity. The caller (Plan 4b dialog) will pass `security.getName()` / `security.getIsin()`.

- [ ] **Step 2: Run — verify it fails** (method missing).

- [ ] **Step 3: Implement.** Add to `CSVExporter` (add imports `name.abuchen.portfolio.snapshot.vorabpauschale.VorabpauschaleResult` and `...LotContribution`; `List`, `File`, `IOException`, `CSVPrinter`, `Values` are already imported). Follow the existing `exportSecurityPrices`/`CSVPrinter` idiom used elsewhere in the file:

```java
    public void exportVorabpauschale(File file, List<VorabpauschaleResult> results, String securityName,
                    String isin) throws IOException
    {
        // single-security export: securityName/isin identify the one security in 'results'
        try (var printer = new CSVPrinter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                        STRATEGY))
        {
            printer.printRecord("Security", "ISIN", "Year", "Month bought", "Shares", "Base value", "Basisertrag",
                            "Vorabpauschale", "Teilfreistellung", "Taxable");

            for (VorabpauschaleResult result : results)
            {
                var factor = result.getTeilfreistellungFactor();
                for (LotContribution lot : result.getLots())
                {
                    var monthBought = lot.getPurchaseDate().getYear() == result.getYear()
                                    ? Integer.toString(lot.getPurchaseDate().getMonthValue())
                                    : "";
                    long taxable = new java.math.BigDecimal(lot.getContribution().getAmount()).multiply(factor)
                                    .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
                    printer.printRecord(securityName, escapeNull(isin), Integer.toString(result.getYear()), monthBought,
                                    Values.Share.format(lot.getShares()),
                                    Values.Amount.format(lot.getBaseValue().getAmount()),
                                    Values.Amount.format(lot.getBasisertrag().getAmount()),
                                    Values.Amount.format(lot.getContribution().getAmount()),
                                    factor.toPlainString(), Values.Amount.format(taxable));
                }
            }
        }
    }
```

NOTE: `STRATEGY` is a REAL package-private constant already defined in `CSVExporter` (`/* package */ static final CSVFormat STRATEGY = ...`, near line 36) and used by the sibling `export*` methods — reference it directly, exactly as written above. It uses the locale list separator, which is why the tests must not assert on the delimiter or formatted amounts.

- [ ] **Step 4: Run — verify PASS.** Adjust the `content.lines().count()` expectation if the CSV writer emits a trailing newline (then it may be 2 or 3 lines — assert `>= 2` and on the specific substrings instead if needed).

- [ ] **Step 5: Commit**
```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/csv/exporter/CSVExporter.java \
        name.abuchen.portfolio.tests/src/name/abuchen/portfolio/datatransfer/csv/exporter/VorabpauschaleCSVExporterTest.java
git commit -m "Add CSVExporter.exportVorabpauschale"
```

---

## Task 4: `CSVExporter.exportGermanTaxGains`

**Files:**
- Modify `CSVExporter.java`
- Add a test to `VorabpauschaleCSVExporterTest.java`

One row per consumed lot within each sale, plus a final total row.

- [ ] **Step 1: Add the failing test**

```java
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
```
Add imports: `name.abuchen.portfolio.snapshot.vorabpauschale.LotGain`, `SaleGain`, `GermanTaxGainResult`.

NOTE: as with Task 3, the `SaleGain.security` is a model `Security`; the exporter takes a `Function<Security,String> nameResolver` so the test can pass `name -> "TestFund"` without building a `Security`. Plan 4b passes `Security::getName`.

- [ ] **Step 2: Run — verify fails.**

- [ ] **Step 3: Implement** in `CSVExporter` (add imports incl. `java.util.function.Function`):

```java
    public void exportGermanTaxGains(File file, GermanTaxGainResult result,
                    java.util.function.Function<name.abuchen.portfolio.model.Security, String> nameResolver)
                    throws IOException
    {
        try (var printer = new CSVPrinter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8),
                        STRATEGY))
        {
            printer.printRecord("Security", "Sale date", "Purchase date", "Shares", "Proceeds", "Cost",
                            "Accumulated Vorabpauschale", "Gain before exemption", "Taxable gain");

            for (SaleGain sale : result.getSales())
            {
                String name = nameResolver.apply(sale.getSecurity());
                for (LotGain lot : sale.getLots())
                {
                    printer.printRecord(name, sale.getSaleDate().toString(), lot.getPurchaseDate().toString(),
                                    Values.Share.format(lot.getShares()),
                                    Values.Amount.format(lot.getProceeds().getAmount()),
                                    Values.Amount.format(lot.getCost().getAmount()),
                                    Values.Amount.format(lot.getAccumulatedVorabpauschale().getAmount()),
                                    Values.Amount.format(lot.getGainBeforeExemption().getAmount()),
                                    Values.Amount.format(lot.getTaxableGain().getAmount()));
                }
            }

            printer.printRecord("TOTAL", "", "", "", "", "", "", "",
                            Values.Amount.format(result.getTotalTaxableGain().getAmount()));
        }
    }
```
Use the SAME CSV strategy/format as Task 3 (`STRATEGY` placeholder — reuse the real one).

- [ ] **Step 4: Run — verify PASS.**

- [ ] **Step 5: Commit**
```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/datatransfer/csv/exporter/CSVExporter.java \
        name.abuchen.portfolio.tests/src/name/abuchen/portfolio/datatransfer/csv/exporter/VorabpauschaleCSVExporterTest.java
git commit -m "Add CSVExporter.exportGermanTaxGains"
```

---

## Task 5: Regression

- [ ] **Step 1: Run the full feature test set:**
```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=OpenLotsResolverTest,VorabpauschaleCalculatorTest,GermanTaxGainCalculatorTest,VorabpauschalePersistenceTest,VorabpauschaleCSVExporterTest
```
Expected: all green.

---

## Self-review checklist (done while writing)

- **Spec coverage (§4.3/§4.4):** Teilfreistellung attribute (Task 2) ✓; exemption factor available for per-lot taxable in CSV (Task 1) ✓; `exportVorabpauschale` with per-lot rows incl. month-bought + taxable (Task 3) ✓; `exportGermanTaxGains` with per-sale/lot rows + total (Task 4) ✓.
- **Type consistency:** the new `VorabpauschaleResult` constructor arg (`teilfreistellungFactor` as 3rd param) is updated at BOTH calculator call sites and in the Task 3 test; exporter method signatures match the Plan 4b callers described in the NOTEs.
- **CSV strategy:** flagged that `STRATEGY` is a placeholder — the implementer must reuse the file's actual CSV format idiom.
- **Deferred to Plan 4b:** the Taxes menu, the two dialogs, the attribute→factor resolver, and marking the client dirty on Finalize.
- **No `$NON-NLS-1$` to be added** (suppressed globally); attribute registration reuses `Messages.*` keys.
