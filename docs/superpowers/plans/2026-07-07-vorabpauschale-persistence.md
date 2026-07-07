# Vorabpauschale — Plan 2: Persistence

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist finalized-year Vorabpauschale results as a first-class `VorabpauschaleEntry` list on `Client`, in BOTH file formats (protobuf binary and XStream XML), fully backward-compatible with existing `.portfolio` files.

**Architecture:** A new immutable `VorabpauschaleEntry` domain object holds one finalized (security, year) snapshot. `Client` owns a nullable `List<VorabpauschaleEntry>` (null-guarded in `doPostLoadInitialization`, exactly like `plans`/`dashboards`). Protobuf gets a new `PVorabpauschaleEntry` message wired through `ProtobufWriter`; XStream gets an alias + immutable-type registration; the schema version is bumped with an additive no-op migration.

**Tech Stack:** Java 21, protobuf (protoc-jar-maven-plugin), XStream, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-07-vorabpauschale-design.md` (section 4.2).

**Depends on:** Plan 1 (the `snapshot/vorabpauschale/` computation package is already committed). Plan 3 (gains report) and Plan 4 (UI) depend on this.

---

## ⚠️ Two hard constraints — read before starting

1. **`-Plocal-dev` skips protobuf generation** (`protobuf.execution.phase=none`). The generated classes under `name.abuchen.portfolio/protos/name/abuchen/portfolio/model/proto/v1/` are checked into git. After editing `client.proto` you MUST regenerate them with the DEFAULT profile (Task 3) and commit the regenerated sources, or nothing that references `PVorabpauschaleEntry` will compile under local-dev.

2. **XStream does NOT run field initializers on load.** A `private List<VorabpauschaleEntry> vorabpauschaleEntries = new ArrayList<>();` initializer is ignored when deserializing an old file — the field will be `null`. That is why the list MUST be null-guarded in `Client.doPostLoadInitialization()` (Task 2), following the existing `plans`/`dashboards` pattern. Do NOT rely on the field initializer.

---

## File structure

- Create `name.abuchen.portfolio/src/name/abuchen/portfolio/model/VorabpauschaleEntry.java`
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java` (field, null-guard, accessors)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/client.proto` (new message + PClient field)
- Regenerate + commit `name.abuchen.portfolio/protos/.../proto/v1/PVorabpauschaleEntry*.java` (and updated `PClient*.java`)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ProtobufWriter.java` (save + load)
- Modify `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java` (version bump, migration case, XStream alias + immutable type)
- Test `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/model/VorabpauschalePersistenceTest.java`

Test command (local-dev is fine for tests once protos are regenerated & committed):
```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=VorabpauschalePersistenceTest
```

---

## Task 1: `VorabpauschaleEntry` domain object

**Files:**
- Create: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/VorabpauschaleEntry.java`

An immutable snapshot of one finalized (security, year) Vorabpauschale. It holds a `Security` reference (for the XStream object graph); protobuf stores/loads the security UUID.

- [ ] **Step 1: Create the class**

```java
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

    public Security getSecurity() { return security; }
    public int getYear() { return year; }
    public BigDecimal getBasiszins() { return basiszins; }
    public BigDecimal getTeilfreistellungFactor() { return teilfreistellungFactor; }
    public Money getYearStartValue() { return yearStartValue; }
    public Money getDistributions() { return distributions; }
    public Money getCappedBasisertrag() { return cappedBasisertrag; }
    public Money getVorabpauschale() { return vorabpauschale; }
    public Money getTaxable() { return taxable; }
    public Instant getFinalizedAt() { return finalizedAt; }
}
```

- [ ] **Step 2: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/model/VorabpauschaleEntry.java
git commit -m "Add VorabpauschaleEntry domain object"
```

---

## Task 2: Wire the list into `Client`

**Files:**
- Modify: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java`

- [ ] **Step 1: Add the field** next to the other nullable lists (near `private List<Dashboard> dashboards;`, around line 66). Do NOT initialize it inline — follow the `plans`/`dashboards` pattern:

```java
    private List<VorabpauschaleEntry> vorabpauschaleEntries;
```

- [ ] **Step 2: Null-guard it** in `doPostLoadInitialization()` (after the `dashboards` guard, around line 113):

```java
        if (vorabpauschaleEntries == null)
            vorabpauschaleEntries = new ArrayList<>();
```

- [ ] **Step 3: Add accessors** near the dashboard accessors (around line 447). Mirror the existing style:

```java
    public List<VorabpauschaleEntry> getVorabpauschaleEntries()
    {
        return Collections.unmodifiableList(vorabpauschaleEntries);
    }

    public void addVorabpauschaleEntry(VorabpauschaleEntry entry)
    {
        vorabpauschaleEntries.add(entry);
    }

    public void removeVorabpauschaleEntry(VorabpauschaleEntry entry)
    {
        vorabpauschaleEntries.remove(entry);
    }
```

Confirm `java.util.Collections` and `java.util.List` are already imported in `Client.java` (they are — `getPlans()` uses them).

- [ ] **Step 4: Compile the core module**

```bash
mvn -f portfolio-app/pom.xml clean compile -Plocal-dev -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio -am -amd
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java
git commit -m "Add Vorabpauschale ledger list to Client"
```

---

## Task 3: Extend `client.proto` and regenerate

**Files:**
- Modify: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/client.proto`
- Regenerate + commit: `name.abuchen.portfolio/protos/name/abuchen/portfolio/model/proto/v1/*` (new `PVorabpauschaleEntry*.java`, updated `PClient*.java`)

- [ ] **Step 1: Add the message** to `client.proto`, immediately before `message PClient {` (around line 300):

```proto
message PVorabpauschaleEntry {
  string security = 1;                  // uuid
  int32 year = 2;
  string basiszins = 3;                 // BigDecimal as plain string
  string teilfreistellungFactor = 4;    // BigDecimal as plain string
  int64 yearStartValue = 5;
  int64 distributions = 6;
  int64 cappedBasisertrag = 7;
  int64 vorabpauschale = 8;
  int64 taxable = 9;
  string currencyCode = 10;             // shared currency for all money fields
  google.protobuf.Timestamp finalizedAt = 11;
}
```

- [ ] **Step 2: Add the repeated field** to `message PClient`. The next free field number after `baseCurrency = 12` (and before `extensions = 99`) is `13`:

```proto
  repeated PVorabpauschaleEntry vorabpauschaleEntries = 13;
```

- [ ] **Step 3: Regenerate the protobuf Java classes.** local-dev skips generation, so use the DEFAULT profile for the core module only:

```bash
mvn -f portfolio-app/pom.xml generate-sources -pl :name.abuchen.portfolio -am
```
Expected: the plugin runs protoc and writes `PVorabpauschaleEntry.java`, `PVorabpauschaleEntryOrBuilder.java`, and regenerated `PClient.java`/`PClientOrBuilder.java` under `name.abuchen.portfolio/protos/name/abuchen/portfolio/model/proto/v1/`.

Verify:
```bash
ls name.abuchen.portfolio/protos/name/abuchen/portfolio/model/proto/v1/PVorabpauschaleEntry*.java
```
Expected: both files exist.

**If the default-profile build cannot run offline** (target-platform/network resolution failure): the protoc-jar plugin itself only needs the protoc artifact, not the Eclipse target platform. Try invoking just the plugin goal for the core module. Inspect the exact goal/execution id first:
```bash
grep -n "protoc-jar-maven-plugin\|<goal>\|<id>" name.abuchen.portfolio/pom.xml
```
Then run that goal directly (typically `mvn -f portfolio-app/pom.xml protobuf:run` or `com.github.os72:protoc-jar-maven-plugin:run` — use the id/goal you found) on `-pl :name.abuchen.portfolio`. If you still cannot regenerate in this environment, STOP and report BLOCKED with the exact error — do NOT hand-write the generated classes.

- [ ] **Step 4: Commit the proto change and regenerated sources together**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/model/client.proto \
        name.abuchen.portfolio/protos/name/abuchen/portfolio/model/proto/v1/
git commit -m "Add PVorabpauschaleEntry to client.proto and regenerate"
```

---

## Task 4: Wire protobuf save/load in `ProtobufWriter`

**Files:**
- Modify: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ProtobufWriter.java`

Context: `save(Client, OutputStream)` builds a `PClient.Builder` via `save*` helpers (~line 861). `load(InputStream)` parses a `PClient` and calls `load*` helpers using a `Lookup` that resolves securities by UUID (`loadInvestmentPlans(newClient, client, lookup)` is the closest analogue — copy its shape). Timestamps: the writer already converts `Instant` ↔ `google.protobuf.Timestamp` for `updatedAt` fields — FIND that existing conversion (grep `Timestamp` in the file) and REUSE the same helper/inline pattern; do not invent a new one.

- [ ] **Step 1: Add the import** near the other `proto.v1` imports:

```java
import name.abuchen.portfolio.model.proto.v1.PVorabpauschaleEntry;
```

- [ ] **Step 2: Add the save helper and call it.** In `save(...)`, next to the other `save*(client, newClient)` calls, add `saveVorabpauschaleEntries(client, newClient);`. Then add the method (place it near `saveTaxonomies`):

```java
    private void saveVorabpauschaleEntries(Client client, PClient.Builder newClient)
    {
        for (VorabpauschaleEntry entry : client.getVorabpauschaleEntries())
        {
            PVorabpauschaleEntry.Builder newEntry = PVorabpauschaleEntry.newBuilder();
            newEntry.setSecurity(entry.getSecurity().getUUID());
            newEntry.setYear(entry.getYear());
            newEntry.setBasiszins(entry.getBasiszins().toPlainString());
            newEntry.setTeilfreistellungFactor(entry.getTeilfreistellungFactor().toPlainString());
            newEntry.setYearStartValue(entry.getYearStartValue().getAmount());
            newEntry.setDistributions(entry.getDistributions().getAmount());
            newEntry.setCappedBasisertrag(entry.getCappedBasisertrag().getAmount());
            newEntry.setVorabpauschale(entry.getVorabpauschale().getAmount());
            newEntry.setTaxable(entry.getTaxable().getAmount());
            newEntry.setCurrencyCode(entry.getVorabpauschale().getCurrencyCode());
            newEntry.setFinalizedAt(/* Instant -> Timestamp using the SAME helper the writer
                                      already uses for updatedAt */ toTimestamp(entry.getFinalizedAt()));
            newClient.addVorabpauschaleEntries(newEntry.build());
        }
    }
```
NOTE: replace `toTimestamp(...)` with the actual conversion the file already uses for `updatedAt` (it may be an inline `Timestamp.newBuilder().setSeconds(...).setNanos(...)` or a private helper). Match it exactly.

- [ ] **Step 3: Add the load helper and call it.** In `load(...)`, after `loadInvestmentPlans(newClient, client, lookup);`, add `loadVorabpauschaleEntries(newClient, client, lookup);`. Then add the method:

```java
    private void loadVorabpauschaleEntries(PClient newClient, Client client, Lookup lookup)
    {
        for (PVorabpauschaleEntry newEntry : newClient.getVorabpauschaleEntriesList())
        {
            Security security = lookup.getSecurity(newEntry.getSecurity());
            if (security == null)
                continue; // skip entries whose security no longer exists

            String currency = newEntry.getCurrencyCode();
            VorabpauschaleEntry entry = new VorabpauschaleEntry(security, newEntry.getYear(),
                            new BigDecimal(newEntry.getBasiszins()),
                            new BigDecimal(newEntry.getTeilfreistellungFactor()),
                            Money.of(currency, newEntry.getYearStartValue()),
                            Money.of(currency, newEntry.getDistributions()),
                            Money.of(currency, newEntry.getCappedBasisertrag()),
                            Money.of(currency, newEntry.getVorabpauschale()),
                            Money.of(currency, newEntry.getTaxable()),
                            /* Timestamp -> Instant using the SAME helper the writer uses */
                            fromTimestamp(newEntry.getFinalizedAt()));
            client.addVorabpauschaleEntry(entry);
        }
    }
```
NOTE: replace `fromTimestamp(...)` with the actual reverse conversion the file already uses for `updatedAt`. Ensure `java.math.BigDecimal`, `name.abuchen.portfolio.money.Money`, and `Security` are imported (Money/Security almost certainly already are; add `BigDecimal` if missing).

- [ ] **Step 4: Compile**

```bash
mvn -f portfolio-app/pom.xml clean compile -Plocal-dev -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio -am -amd
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/model/ProtobufWriter.java
git commit -m "Persist Vorabpauschale entries in protobuf format"
```

---

## Task 5: `ClientFactory` — version bump, migration, XStream registration

**Files:**
- Modify: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java` (CURRENT_VERSION)
- Modify: `name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java`

- [ ] **Step 1: Bump the version.** In `Client.java` change:

```java
    public static final int CURRENT_VERSION = 70;
```
to
```java
    public static final int CURRENT_VERSION = 71;
```

- [ ] **Step 2: Extend the migration switch.** In `ClientFactory.upgradeModel(...)`, the tail currently reads:

```java
            case 69: // NOSONAR
                // add (optional) weight to client filter

                client.setVersion(Client.CURRENT_VERSION);
                break;
            case Client.CURRENT_VERSION:
                break;
```
Change it to insert a `case 70` that falls through (the change is purely additive — old files simply gain an empty ledger, so no data migration is needed):

```java
            case 69: // NOSONAR
                // add (optional) weight to client filter
            case 70: // NOSONAR
                // added Vorabpauschale ledger (additive, no data migration required)

                client.setVersion(Client.CURRENT_VERSION);
                break;
            case Client.CURRENT_VERSION:
                break;
```

- [ ] **Step 3: Register the XStream alias.** In `ClientFactory`, next to the other `xstream.alias(...)` calls (around line 1915, near `investment-plan`), add:

```java
        xstream.alias("vorabpauschale-entry", VorabpauschaleEntry.class);
```

- [ ] **Step 4: Register the immutable type.** Next to the other `xstreamWriter.addImmutableType(...)` calls (around line 1860, near `Dashboard.class`), add:

```java
            xstreamWriter.addImmutableType(VorabpauschaleEntry.class, false);
```

Confirm `VorabpauschaleEntry` resolves — it is in the same package (`name.abuchen.portfolio.model`) as `ClientFactory`, so no import is needed.

- [ ] **Step 5: Compile**

```bash
mvn -f portfolio-app/pom.xml clean compile -Plocal-dev -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio -am -amd
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/model/Client.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/model/ClientFactory.java
git commit -m "Persist Vorabpauschale entries in XML and bump file version"
```

---

## Task 6: Round-trip tests

**Files:**
- Create: `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/model/VorabpauschalePersistenceTest.java`

Covers: protobuf round-trip, XML round-trip, and backward compatibility (a client with no ledger loads with an empty, non-null list). Mirrors `ProtobufWriterAdditionalTest` (programmatic client + `ProtobufWriter.save/load`) and uses `ClientTestUtilities.toString` + `ClientFactory.load(Reader)` for XML.

- [ ] **Step 1: Write the tests**

```java
package name.abuchen.portfolio.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;

import org.junit.Test;

import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class VorabpauschalePersistenceTest
{
    private Client clientWithEntry()
    {
        Client client = new Client();
        Security security = new Security();
        security.setName("Test ETF");
        security.setCurrencyCode(CurrencyUnit.EUR);
        client.addSecurity(security);

        VorabpauschaleEntry entry = new VorabpauschaleEntry(security, 2024, new BigDecimal("2.53"),
                        new BigDecimal("0.70"), Money.of("EUR", 10_000_00), Money.of("EUR", 0),
                        Money.of("EUR", 177_10), Money.of("EUR", 177_10), Money.of("EUR", 123_97),
                        Instant.parse("2025-01-02T00:00:00Z"));
        client.addVorabpauschaleEntry(entry);
        return client;
    }

    private void assertEntry(Client client)
    {
        assertThat(client.getVorabpauschaleEntries().size(), is(1));
        VorabpauschaleEntry e = client.getVorabpauschaleEntries().get(0);
        assertThat(e.getSecurity().getName(), is("Test ETF"));
        assertThat(e.getYear(), is(2024));
        assertThat(e.getBasiszins(), is(new BigDecimal("2.53")));
        assertThat(e.getTeilfreistellungFactor(), is(new BigDecimal("0.70")));
        assertThat(e.getYearStartValue(), is(Money.of("EUR", 10_000_00)));
        assertThat(e.getCappedBasisertrag(), is(Money.of("EUR", 177_10)));
        assertThat(e.getVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(e.getTaxable(), is(Money.of("EUR", 123_97)));
        assertThat(e.getFinalizedAt(), is(Instant.parse("2025-01-02T00:00:00Z")));
    }

    @Test
    public void testProtobufRoundtrip() throws IOException
    {
        Client client = clientWithEntry();

        ProtobufWriter writer = new ProtobufWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.save(client, out);
        out.close();

        Client reloaded = writer.load(new ByteArrayInputStream(out.toByteArray()));
        assertEntry(reloaded);
    }

    @Test
    public void testXmlRoundtrip() throws IOException
    {
        Client client = clientWithEntry();

        String xml = ClientTestUtilities.toString(client);
        Client reloaded = ClientFactory.load(new StringReader(xml));
        assertEntry(reloaded);
    }

    @Test
    public void testOldFileWithoutLedgerLoadsEmpty() throws IOException
    {
        // a client with no ledger element must load with an empty, non-null list
        Client client = new Client();
        Security security = new Security();
        security.setName("Plain");
        security.setCurrencyCode(CurrencyUnit.EUR);
        client.addSecurity(security);

        String xml = ClientTestUtilities.toString(client);
        Client reloaded = ClientFactory.load(new StringReader(xml));

        assertThat(reloaded.getVorabpauschaleEntries().isEmpty(), is(true));
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=VorabpauschalePersistenceTest
```
Expected: 3 tests pass.

VERIFICATION NOTES if a test fails:
- **XStream + `Instant`:** if the XML round-trip fails on `finalizedAt`, check whether XStream needs `Instant` registered. `Transaction.updatedAt` is an `Instant` that already persists, so the converter exists — confirm how it's registered (grep `Instant` in `ClientFactory`) and, if the ledger entry needs it, ensure the same registration applies. Do NOT change `Instant` to another type without reporting it.
- **`BigDecimal.equals` is scale-sensitive:** `new BigDecimal("0.70")` ≠ `new BigDecimal("0.7")`. The protobuf path stores/loads via `toPlainString()`/`new BigDecimal(String)`, which preserves scale, so the asserts above should hold. If XStream normalizes the scale differently, adjust the assertion to compare with `compareTo(...) == 0` and note it.
- **`ClientFactory.load(Reader)` id-references:** if loading throws about references, use `ClientTestUtilities.toString(client, true)` + `ClientFactory.load(reader, true)` (the id-references variant) consistently for the XML tests.

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/model/VorabpauschalePersistenceTest.java
git commit -m "Test Vorabpauschale entry persistence round-trips"
```

---

## Task 7: Full regression

- [ ] **Step 1: Run the existing persistence tests** to prove backward compatibility didn't break (these load real sample `.portfolio` files):

```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=ProtobufWriterTest,ProtobufWriterAdditionalTest,VorabpauschalePersistenceTest
```
Expected: all green. `ProtobufWriterTest` round-trips checked-in sample files; they must still pass with the bumped version and new (empty) ledger.

- [ ] **Step 2: If green, the plan is complete.** If `ProtobufWriterTest` fails because sample files are re-serialized at version 71, inspect whether the test compares version numbers; report the specific failure rather than editing sample files blindly.

---

## Self-review checklist (done while writing)

- **Spec coverage (§4.2):** first-class `VorabpauschaleEntry` on `Client` (Tasks 1–2) ✓; protobuf persistence (Tasks 3–4) ✓; XStream persistence + version bump + additive migration (Task 5) ✓; per-(security, year) granularity, per-lot derived-not-stored ✓ (entry has no lot fields); backward compatibility (Task 2 null-guard + Task 6 test) ✓.
- **Type consistency:** `VorabpauschaleEntry` constructor arg order is identical in the class (Task 1), the protobuf load (Task 4), and the tests (Task 6). Accessor names (`getVorabpauschaleEntries`/`addVorabpauschaleEntry`/`removeVorabpauschaleEntry`) match across Client, ProtobufWriter, and tests. Proto field `vorabpauschaleEntries` → generated `getVorabpauschaleEntriesList()`/`addVorabpauschaleEntries(...)`.
- **Risk points flagged inline:** protobuf regen under default profile (Task 3), Instant/XStream and BigDecimal scale (Task 6 notes), timestamp helper reuse (Task 4 notes).
- **No new i18n keys**; no `$NON-NLS-1$` comments to be added.
