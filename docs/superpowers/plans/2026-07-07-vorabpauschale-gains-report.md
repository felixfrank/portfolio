# Vorabpauschale — Plan 3: German Realized-Gains Calculator

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compute, for a chosen year, the German taxable capital gain of each sale — proceeds minus purchase cost minus the *accumulated Vorabpauschale* that attaches to the FIFO-consumed lots, after Teilfreistellung — as a pure, fully-tested calculator.

**Architecture:** A single forward FIFO simulation per security walks the transaction timeline. Open lots carry their purchase cost and an accumulating Vorabpauschale balance. At each past year-end that has a finalized ledger entry, that year's Vorabpauschale is distributed across the then-open lots in proportion to `shares × timeFactor`; the per-share proportionality constant cancels, so no quotes are needed for accumulation. When a sale in the target year consumes lots, each consumed portion carries its share of accumulated Vorabpauschale, which raises the cost basis.

**Tech Stack:** Java 21, JUnit 4, PP `Money`/`Values`/`CurrencyConverter`, `VorabpauschaleEntry` ledger (Plan 2), junit builders.

**Spec:** `docs/superpowers/specs/2026-07-07-vorabpauschale-design.md` (section 1.B, 4.1 `GermanTaxGainCalculator`).

**Depends on:** Plan 1 (`snapshot/vorabpauschale/`) and Plan 2 (`Client.getVorabpauschaleEntries()`), both committed. Plan 4 (UI/CSV) depends on this.

---

## The algorithm (read first)

For one security and target year `Y`:

1. Collect the security's portfolio transactions across ALL portfolios with date ≤ 31 Dec `Y`, sorted by `TradeCollector.BY_DATE_AND_TYPE`. Transfers between the user's own portfolios are investor-level no-ops and are ignored (same rule as `OpenLotsResolver`).
2. Build a chronological event stream = those transactions PLUS a synthetic "year-end Z" event at 31 Dec of every year `Z` in `[firstPurchaseYear, Y-1]` that has a ledger entry for this security. Year-end events sort AFTER all transactions dated in year `Z`.
3. Maintain a FIFO deque of open lots. Each lot carries: the purchase transaction, `remainingShares`, `originalShares`, total purchase cost (EUR, gross), and accumulated Vorabpauschale (EUR).
4. Process events in order:
   - **Purchase** (BUY / DELIVERY_INBOUND): push a lot with `cost = purchase.getGrossValue()` converted to EUR at the purchase date, `accumulated = 0`.
   - **Sale before year Y** (SELL / DELIVERY_OUTBOUND, year < Y): FIFO-consume shares (drop cost & accumulated proportionally). No result emitted — that gain belongs to an earlier year.
   - **Sale in year Y**: FIFO-consume; for each consumed lot portion emit a `LotGain` line: `proceeds` (that portion's share of the sale's gross value, EUR), `cost` (consumed fraction of the lot's cost), `accumulatedVorabpauschale` (consumed fraction of the lot's accumulated balance).
   - **Year-end Z**: distribute `ledger(security, Z).getVorabpauschale()` across the currently open lots in proportion to `remainingShares_i × timeFactor(purchaseDate_i, Z)`, adding each lot's share to its accumulated balance. `timeFactor = 1` if the lot was purchased before year `Z`, else `(13 − purchaseMonth)/12`.
5. Per emitted `LotGain`: `gainBeforeExemption = proceeds − cost − accumulatedVorabpauschale`; `taxableGain = gainBeforeExemption × teilfreistellungFactor(security)`. (Teilfreistellung applies symmetrically to gains and losses.)
6. Group `LotGain`s by sale transaction into `SaleGain`; sum everything into a `GermanTaxGainResult`.

Why proportional-by-`shares × timeFactor` is correct and needs no quotes: for a given year `Z`, each lot's Basisertrag is `perShareStart_Z × shares_i × basiszins × 0.70 × timeFactor_i`. The finalized total to distribute is the ledger's `vorabpauschale(Z)`. The ratio `Basisertrag_i / Σ Basisertrag_j` reduces to `(shares_i × timeFactor_i) / Σ(shares_j × timeFactor_j)` — every per-share/rate factor cancels.

**Documented v1 choices** (record in the class Javadoc):
- Proceeds and cost both use `getGrossValue()` (excludes fees and taxes on both sides); transaction fees are not separately deducted in v1.
- Teilfreistellung is applied to the net gain (after the Vorabpauschale basis step), symmetrically to losses.
- Only sales with a resolvable FIFO history contribute; a sale with insufficient prior holdings is reported as a warning (mirrors `OpenLotsResolver`'s oversell handling), not a crash.

---

## File structure

- Create `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/LotGain.java`
- Create `.../snapshot/vorabpauschale/SaleGain.java`
- Create `.../snapshot/vorabpauschale/GermanTaxGainResult.java`
- Create `.../snapshot/vorabpauschale/GermanTaxGainCalculator.java`
- Test `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/GermanTaxGainCalculatorTest.java`

Test command:
```bash
export MAVEN_OPTS="-Xmx4g"
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=GermanTaxGainCalculatorTest
```

---

## Task 1: Result value objects

**Files:**
- Create `LotGain.java`, `SaleGain.java`, `GermanTaxGainResult.java` in `snapshot/vorabpauschale/`.

- [ ] **Step 1: `LotGain.java`**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;

import name.abuchen.portfolio.money.Money;

/** One FIFO-consumed purchase lot within a sale, with its taxable gain. */
public final class LotGain
{
    private final LocalDate purchaseDate;
    private final long shares;
    private final Money proceeds;
    private final Money cost;
    private final Money accumulatedVorabpauschale;
    private final Money gainBeforeExemption;
    private final Money taxableGain;

    public LotGain(LocalDate purchaseDate, long shares, Money proceeds, Money cost,
                    Money accumulatedVorabpauschale, Money gainBeforeExemption, Money taxableGain)
    {
        this.purchaseDate = purchaseDate;
        this.shares = shares;
        this.proceeds = proceeds;
        this.cost = cost;
        this.accumulatedVorabpauschale = accumulatedVorabpauschale;
        this.gainBeforeExemption = gainBeforeExemption;
        this.taxableGain = taxableGain;
    }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public long getShares() { return shares; }
    public Money getProceeds() { return proceeds; }
    public Money getCost() { return cost; }
    public Money getAccumulatedVorabpauschale() { return accumulatedVorabpauschale; }
    public Money getGainBeforeExemption() { return gainBeforeExemption; }
    public Money getTaxableGain() { return taxableGain; }
}
```

- [ ] **Step 2: `SaleGain.java`**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;

/** One sale in the target year, with its FIFO-consumed lots and totals. */
public final class SaleGain
{
    private final Security security;
    private final LocalDate saleDate;
    private final long shares;
    private final Money proceeds;
    private final Money cost;
    private final Money accumulatedVorabpauschale;
    private final Money taxableGain;
    private final List<LotGain> lots;

    public SaleGain(Security security, LocalDate saleDate, long shares, Money proceeds, Money cost,
                    Money accumulatedVorabpauschale, Money taxableGain, List<LotGain> lots)
    {
        this.security = security;
        this.saleDate = saleDate;
        this.shares = shares;
        this.proceeds = proceeds;
        this.cost = cost;
        this.accumulatedVorabpauschale = accumulatedVorabpauschale;
        this.taxableGain = taxableGain;
        this.lots = Collections.unmodifiableList(new ArrayList<>(lots));
    }

    public Security getSecurity() { return security; }
    public LocalDate getSaleDate() { return saleDate; }
    public long getShares() { return shares; }
    public Money getProceeds() { return proceeds; }
    public Money getCost() { return cost; }
    public Money getAccumulatedVorabpauschale() { return accumulatedVorabpauschale; }
    public Money getTaxableGain() { return taxableGain; }
    public List<LotGain> getLots() { return lots; }
}
```

- [ ] **Step 3: `GermanTaxGainResult.java`**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.money.Money;

/** All sales in the target year with the total taxable gain. */
public final class GermanTaxGainResult
{
    private final int year;
    private final List<SaleGain> sales;
    private final Money totalTaxableGain;
    private final List<String> warnings;

    public GermanTaxGainResult(int year, List<SaleGain> sales, Money totalTaxableGain, List<String> warnings)
    {
        this.year = year;
        this.sales = Collections.unmodifiableList(new ArrayList<>(sales));
        this.totalTaxableGain = totalTaxableGain;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public int getYear() { return year; }
    public List<SaleGain> getSales() { return sales; }
    public Money getTotalTaxableGain() { return totalTaxableGain; }
    public List<String> getWarnings() { return warnings; }
}
```

- [ ] **Step 4: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/LotGain.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/SaleGain.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/GermanTaxGainResult.java
git commit -m "Add German tax gains result value objects"
```

---

## Task 2: `GermanTaxGainCalculator` + first test (single-year accumulation)

**Files:**
- Create `.../snapshot/vorabpauschale/GermanTaxGainCalculator.java`
- Test `.../snapshot/vorabpauschale/GermanTaxGainCalculatorTest.java`

Signature: `compute(Client client, int year, java.util.function.Function<Security, BigDecimal> teilfreistellungResolver, CurrencyConverter converter) -> GermanTaxGainResult`. The resolver returns each security's Teilfreistellung factor (Plan 4 supplies it from the attribute/override; tests pass a lambda).

- [ ] **Step 1: Write the failing test**

Scenario: 1 EUR fund. Buy 100 sh in 2023 for 10 000.00 (gross). One finalized ledger entry: 2024 Vorabpauschale = 177.10 on the whole 100-share holding. Sell all 100 in 2025 for 13 000.00 (gross). Teilfreistellung 1.0.
- accumulated Vorabpauschale on the lot = 177.10 (only lot, only year).
- gainBeforeExemption = 13 000.00 − 10 000.00 − 177.10 = 2 822.90; taxable = 2 822.90.

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.VorabpauschaleEntry;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class GermanTaxGainCalculatorTest
{
    private static final long SHARE = Values.Share.factor();
    private final TestCurrencyConverter eur = new TestCurrencyConverter(CurrencyUnit.EUR);

    private VorabpauschaleEntry ledger(Security security, int year, long vorabCents)
    {
        return new VorabpauschaleEntry(security, year, new BigDecimal("2.53"), BigDecimal.ONE,
                        Money.of("EUR", 10_000_00), Money.of("EUR", 0), Money.of("EUR", vorabCents),
                        Money.of("EUR", vorabCents), Money.of("EUR", vorabCents),
                        Instant.parse(year + 1 + "-01-02T00:00:00Z"));
    }

    @Test
    public void testAccumulatedVorabpauschaleRaisesBasis()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 13_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2024, 177_10));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 2_822_90)));
        assertThat(r.getTotalTaxableGain(), is(Money.of("EUR", 2_822_90)));
    }
}
```

VERIFICATION: confirm `new SecurityBuilder("EUR").addTo(client)` and `PortfolioBuilder.buy/sell(security, date, shares, amount)` signatures (they were used in Plan 1 tests). Confirm `VorabpauschaleEntry` constructor arg order from Plan 2.

- [ ] **Step 2: Run to verify it fails** (`GermanTaxGainCalculator` missing).

- [ ] **Step 3: Implement `GermanTaxGainCalculator`**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Function;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.model.VorabpauschaleEntry;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.trades.TradeCollector;

/**
 * Computes the German taxable capital gain (proceeds − cost − accumulated
 * Vorabpauschale, after Teilfreistellung) for every sale in a target year,
 * via a forward FIFO simulation that accrues each finalized year's
 * Vorabpauschale onto the then-open lots. Pure computation; no persistence, no
 * UI. All money is in the converter's term currency (EUR).
 *
 * v1 conventions: proceeds and cost both use gross value (fees/taxes excluded);
 * Teilfreistellung is applied to the net gain, symmetrically to losses.
 */
public final class GermanTaxGainCalculator
{
    private static final class Lot
    {
        private final PortfolioTransaction purchase;
        private long remainingShares;
        private final long originalShares;
        private long costCents;          // for the remaining shares
        private long accumulatedCents;   // Vorabpauschale accrued onto remaining shares

        private Lot(PortfolioTransaction purchase, long costCents)
        {
            this.purchase = purchase;
            this.remainingShares = purchase.getShares();
            this.originalShares = purchase.getShares();
            this.costCents = costCents;
        }
    }

    private GermanTaxGainCalculator()
    {
    }

    public static GermanTaxGainResult compute(Client client, int year,
                    Function<Security, BigDecimal> teilfreistellungResolver, CurrencyConverter converter)
    {
        String eur = converter.getTermCurrency();
        List<SaleGain> sales = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long totalTaxable = 0;

        for (Security security : client.getSecurities())
        {
            BigDecimal exemption = teilfreistellungResolver.apply(security);
            List<SaleGain> forSecurity = computeForSecurity(client, security, year, exemption, converter, warnings);
            sales.addAll(forSecurity);
            for (SaleGain s : forSecurity)
                totalTaxable += s.getTaxableGain().getAmount();
        }

        return new GermanTaxGainResult(year, sales, Money.of(eur, totalTaxable), warnings);
    }

    private static List<SaleGain> computeForSecurity(Client client, Security security, int year,
                    BigDecimal exemption, CurrencyConverter converter, List<String> warnings)
    {
        String eur = converter.getTermCurrency();
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        // portfolio transactions across all portfolios, up to the target year end
        List<PortfolioTransaction> txs = new ArrayList<>();
        for (TransactionPair<?> pair : security.getTransactions(client))
        {
            if (pair.getTransaction() instanceof PortfolioTransaction tx
                            && !tx.getDateTime().toLocalDate().isAfter(yearEnd))
                txs.add(tx);
        }
        if (txs.isEmpty())
            return Collections.emptyList();

        Collections.sort(txs, (a, b) -> TradeCollector.BY_DATE_AND_TYPE.compare(
                        new TransactionPair<>(dummyOwner(client, security), a),
                        new TransactionPair<>(dummyOwner(client, security), b)));

        // ledger vorabpauschale (EUR cents) per year for this security
        java.util.Map<Integer, Long> ledgerByYear = new java.util.HashMap<>();
        for (VorabpauschaleEntry e : client.getVorabpauschaleEntries())
        {
            if (e.getSecurity() == security)
                ledgerByYear.put(e.getYear(), e.getVorabpauschale().getAmount());
        }

        Deque<Lot> open = new ArrayDeque<>();
        List<SaleGain> result = new ArrayList<>();

        int nextYearEndToApply = txs.get(0).getDateTime().getYear();

        for (PortfolioTransaction tx : txs)
        {
            int txYear = tx.getDateTime().getYear();

            // apply all year-ends strictly before this transaction's year
            while (nextYearEndToApply < txYear && nextYearEndToApply < year)
            {
                applyYearEnd(open, nextYearEndToApply, ledgerByYear);
                nextYearEndToApply++;
            }

            if (tx.getType().isPurchase())
            {
                long costCents = converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue()).getAmount();
                open.addLast(new Lot(tx, costCents));
            }
            else if (tx.getType().isLiquidation())
            {
                boolean inTargetYear = txYear == year;
                long proceedsCents = converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue()).getAmount();
                consumeSale(open, tx, proceedsCents, inTargetYear, security, exemption, eur, result, warnings);
            }
        }

        return result;
    }

    private static void applyYearEnd(Deque<Lot> open, int yearZ, java.util.Map<Integer, Long> ledgerByYear)
    {
        Long vorabCents = ledgerByYear.get(yearZ);
        if (vorabCents == null || open.isEmpty())
            return;

        // weight_i = remainingShares_i * timeFactor_i ; timeFactor in 1/12 units to stay integral
        List<Lot> lots = new ArrayList<>(open);
        List<BigDecimal> weights = new ArrayList<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (Lot lot : lots)
        {
            int months = lot.purchase.getDateTime().getYear() < yearZ ? 12
                            : 13 - lot.purchase.getDateTime().getMonthValue();
            BigDecimal w = BigDecimal.valueOf(lot.remainingShares).multiply(BigDecimal.valueOf(months));
            weights.add(w);
            totalWeight = totalWeight.add(w);
        }
        if (totalWeight.signum() == 0)
            return;

        long allocated = 0;
        for (int i = 0; i < lots.size(); i++)
        {
            long share;
            if (i < lots.size() - 1)
            {
                share = BigDecimal.valueOf(vorabCents).multiply(weights.get(i))
                                .divide(totalWeight, 0, RoundingMode.HALF_UP).longValue();
                allocated += share;
            }
            else
            {
                share = vorabCents - allocated;
            }
            lots.get(i).accumulatedCents += share;
        }
    }

    private static void consumeSale(Deque<Lot> open, PortfolioTransaction sale, long proceedsCents,
                    boolean inTargetYear, Security security, BigDecimal exemption, String eur, List<SaleGain> result,
                    List<String> warnings)
    {
        long sharesToSell = sale.getShares();
        long totalShares = sharesToSell;
        List<LotGain> lotGains = new ArrayList<>();

        while (sharesToSell > 0 && !open.isEmpty())
        {
            Lot lot = open.peekFirst();
            long take = Math.min(sharesToSell, lot.remainingShares);

            // fraction of THIS lot consumed
            long costTaken = fraction(lot.costCents, take, lot.remainingShares);
            long accumTaken = fraction(lot.accumulatedCents, take, lot.remainingShares);
            // fraction of the SALE proceeds for these shares
            long proceedsTaken = fraction(proceedsCents, take, totalShares);

            if (inTargetYear)
            {
                long gainBefore = proceedsTaken - costTaken - accumTaken;
                long taxable = BigDecimal.valueOf(gainBefore).multiply(exemption)
                                .setScale(0, RoundingMode.HALF_UP).longValue();
                lotGains.add(new LotGain(lot.purchase.getDateTime().toLocalDate(), take,
                                Money.of(eur, proceedsTaken), Money.of(eur, costTaken), Money.of(eur, accumTaken),
                                Money.of(eur, gainBefore), Money.of(eur, taxable)));
            }

            lot.costCents -= costTaken;
            lot.accumulatedCents -= accumTaken;
            lot.remainingShares -= take;
            sharesToSell -= take;
            if (lot.remainingShares == 0)
                open.removeFirst();
        }

        if (sharesToSell > 0)
            warnings.add("More shares sold than held for " + security.getName() + " on "
                            + Values.DateTime.format(sale.getDateTime()));

        if (inTargetYear && !lotGains.isEmpty())
        {
            long shares = totalShares - sharesToSell;
            long proceeds = sum(lotGains, LotGain::getProceeds);
            long cost = sum(lotGains, LotGain::getCost);
            long accum = sum(lotGains, LotGain::getAccumulatedVorabpauschale);
            long taxable = sum(lotGains, LotGain::getTaxableGain);
            result.add(new SaleGain(security, sale.getDateTime().toLocalDate(), shares, Money.of(eur, proceeds),
                            Money.of(eur, cost), Money.of(eur, accum), Money.of(eur, taxable), lotGains));
        }
    }

    private static long fraction(long total, long part, long whole)
    {
        if (whole == 0)
            return 0;
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(part))
                        .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValue();
    }

    private static long sum(List<LotGain> lots, java.util.function.Function<LotGain, Money> field)
    {
        long s = 0;
        for (LotGain lot : lots)
            s += field.apply(lot).getAmount();
        return s;
    }

    private static name.abuchen.portfolio.model.Portfolio dummyOwner(Client client, Security security)
    {
        // BY_DATE_AND_TYPE only inspects the transaction, not the owner; any
        // portfolio works. Use the first portfolio if present, else a throwaway.
        return client.getPortfolios().isEmpty() ? new name.abuchen.portfolio.model.Portfolio()
                        : client.getPortfolios().get(0);
    }
}
```

NOTE for the implementer: `TransactionPair`'s constructor/generics and `TradeCollector.BY_DATE_AND_TYPE`'s exact signature — verify by reading `OpenLotsResolver` (Plan 1) which already sorts `List<TransactionPair<?>>` with `TradeCollector.BY_DATE_AND_TYPE`. If wrapping bare `PortfolioTransaction`s in `TransactionPair` for sorting is awkward, instead reuse the SAME approach `OpenLotsResolver` uses: sort the `List<TransactionPair<?>>` from `security.getTransactions(client)` directly with `TradeCollector.BY_DATE_AND_TYPE`, then iterate and filter `PortfolioTransaction`s — that avoids constructing throwaway pairs and `dummyOwner`. Prefer that cleaner approach and delete `dummyOwner` if you do.

- [ ] **Step 4: Run to verify PASS.** Commit: "Add GermanTaxGainCalculator with per-lot Vorabpauschale accumulation".

---

## Task 3: FIFO partial sale — older lot carries more accumulation

**Files:** add test.

Scenario: EUR fund. Buy 100 sh 2022 @ 100.00 (cost 10 000.00); buy 100 sh 2023 @ 120.00 (cost 12 000.00). Ledger: 2022 Vorab 100.00, 2023 Vorab 200.00, 2024 Vorab 300.00. Sell 100 sh in 2025 for 15 000.00 (gross). Teilfreistellung 1.0.

Work out accumulation on the FIRST (2022) lot, which FIFO consumes:
- End 2022: only the 2022 lot is open (the 2023 lot doesn't exist yet). weight = 100×12. Its share of 100.00 = 100.00.
- End 2023: both lots open. 2022 lot weight 100×12=1200; 2023 lot bought June 2023 → months=13−6=7 → weight 100×7=700. Total 1900. 2022 lot share of 200.00 = 200.00×1200/1900 = 126.3157… → 126.32 (HALF_UP); 2023 lot gets remainder 73.68.
- End 2024: both lots open, both full year → weights 1200 and 1200, total 2400. 2022 lot share of 300.00 = 150.00.
- 2022 lot accumulated = 100.00 + 126.32 + 150.00 = 376.32.
- Sell 100 sh 2025 consumes exactly the 2022 lot. gainBefore = 15 000.00 − 10 000.00 − 376.32 = 4 623.68; taxable = 4 623.68.

```java
    @Test
    public void testFifoConsumesOldestLotWithItsAccumulation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2022-03-01", 100 * SHARE, 10_000_00) //
                        .buy(security, "2023-06-01", 100 * SHARE, 12_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 15_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2022, 100_00));
        client.addVorabpauschaleEntry(ledger(security, 2023, 200_00));
        client.addVorabpauschaleEntry(ledger(security, 2024, 300_00));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getShares(), is(100 * SHARE));
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 376_32)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 4_623_68)));
    }
```

- [ ] **Step 1: Add the test. Step 2: run.** If the rounding on the 2023 year-end split lands a cent differently, recompute `200.00 × 1200 / 1900` HALF_UP = 126.32 and adjust ONLY if the hand-calc shows otherwise; report any discrepancy. Commit: "Test FIFO consumes oldest lot with its accumulated Vorabpauschale".

---

## Task 4: Bought-and-sold same year has no accumulation

**Files:** add test.

Buy 50 sh in 2025 for 5 000.00; sell same 50 in 2025 for 5 500.00. No ledger entries. gainBefore = 500.00; taxable (×1.0) = 500.00; accumulated = 0.

```java
    @Test
    public void testSameYearBuyAndSellHasNoAccumulation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2025-02-01", 50 * SHARE, 5_000_00) //
                        .sell(security, "2025-09-01", 50 * SHARE, 5_500_00) //
                        .addTo(client);

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> BigDecimal.ONE, eur);

        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getAccumulatedVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", 500_00)));
    }
```

- [ ] **Step 1: Add + run + commit** "Test same-year buy/sell has no accumulation".

---

## Task 5: Teilfreistellung applies to the gain

**Files:** add test.

Reuse the Task 2 scenario but exemption 0.70: taxable = 2 822.90 × 0.70 = 1 976.03.

```java
    @Test
    public void testTeilfreistellungAppliedToGain()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(security, "2025-06-01", 100 * SHARE, 13_000_00) //
                        .addTo(client);
        client.addVorabpauschaleEntry(ledger(security, 2024, 177_10));

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> new BigDecimal("0.70"), eur);

        // 2822.90 * 0.70 = 1976.03
        assertThat(r.getSales().get(0).getTaxableGain(), is(Money.of("EUR", 1_976_03)));
    }
```

- [ ] **Step 1: Add + run + commit** "Test Teilfreistellung applied to realized gain".

---

## Task 6: A loss also gets Teilfreistellung, and only target-year sales appear

**Files:** add test.

Buy 100 sh 2023 for 10 000.00; sell 100 in 2025 for 8 000.00 (a loss). No ledger. exemption 0.70. gainBefore = −2 000.00; taxable = −1 400.00. Also add an unrelated sale in 2024 to prove only 2025 sales are returned.

```java
    @Test
    public void testLossWithExemptionAndYearFiltering()
    {
        Client client = new Client();
        Security a = new SecurityBuilder("EUR").addTo(client);
        Security b = new SecurityBuilder("EUR").addTo(client);
        new PortfolioBuilder() //
                        .buy(a, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .sell(a, "2025-06-01", 100 * SHARE, 8_000_00) //
                        .buy(b, "2022-01-01", 10 * SHARE, 1_000_00) //
                        .sell(b, "2024-01-01", 10 * SHARE, 1_500_00) // different year -> excluded
                        .addTo(client);

        GermanTaxGainResult r = GermanTaxGainCalculator.compute(client, 2025, s -> new BigDecimal("0.70"), eur);

        assertThat(r.getSales().size(), is(1));
        SaleGain sale = r.getSales().get(0);
        assertThat(sale.getSecurity(), is(a));
        // -2000.00 * 0.70 = -1400.00
        assertThat(sale.getTaxableGain(), is(Money.of("EUR", -1_400_00)));
        assertThat(r.getTotalTaxableGain(), is(Money.of("EUR", -1_400_00)));
    }
```

- [ ] **Step 1: Add + run + commit** "Test loss with exemption and target-year filtering".

---

## Task 7: Full regression

- [ ] **Step 1: Run the whole vorabpauschale test package:**

```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=OpenLotsResolverTest,VorabpauschaleCalculatorTest,GermanTaxGainCalculatorTest,VorabpauschalePersistenceTest
```
Expected: all green.

---

## Self-review checklist (done while writing)

- **Spec coverage (§1.B, §4.1):** `GermanTaxGainCalculator` produces per-sale taxable gains net of accumulated Vorabpauschale via FIFO (Tasks 2–3) ✓; Teilfreistellung on the gain (Task 5) ✓; loss symmetry + year filtering (Task 6) ✓; same-year no-accumulation edge (Task 4) ✓; warnings not crashes on oversell ✓.
- **Type consistency:** `compute(Client, int, Function<Security,BigDecimal>, CurrencyConverter)`; `SaleGain`/`LotGain`/`GermanTaxGainResult` getters used identically in tests and calculator; `VorabpauschaleEntry` getters match Plan 2.
- **Derivation not storage:** accumulation is recomputed from the ledger totals + transaction history each run; nothing new is persisted. ✓
- **Flagged for implementer:** the `TransactionPair`/`BY_DATE_AND_TYPE` sorting should reuse `OpenLotsResolver`'s exact approach (sort `List<TransactionPair<?>>` directly) rather than the throwaway-pair variant shown — noted in Task 2.
- **Rounding:** all per-lot splits use HALF_UP with a last-item remainder for the year-end allocation, mirroring Plan 1's reconciliation.
