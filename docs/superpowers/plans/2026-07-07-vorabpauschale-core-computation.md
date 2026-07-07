# Vorabpauschale — Plan 1: Core Computation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure, fully unit-tested engine that computes the Vorabpauschale for one security and year, with a per-lot breakdown — no persistence, no UI.

**Architecture:** A date-bounded FIFO lot resolver (`OpenLotsResolver`) determines the shares held at year-end across all portfolios (transfers are investor-level no-ops and are ignored, preserving original acquisition dates). `VorabpauschaleCalculator` applies §18 InvStG: Basisertrag = year-start NAV × Basiszins × 0.70 × time-factor per lot, capped at the aggregate Wertsteigerung, reduced by distributions, then Teilfreistellung. Results are plain value objects.

**Tech Stack:** Java 21, JUnit 4, PP's `Money`/`Values`/`CurrencyConverter`, junit builders (`SecurityBuilder`, `PortfolioBuilder`, `AccountBuilder`, `TestCurrencyConverter`).

**Spec:** `docs/superpowers/specs/2026-07-07-vorabpauschale-design.md` (sections 2–4.1).

**Overall subsystem sequence (context):** Plan 1 = core computation (this doc) · Plan 2 = persistence (`VorabpauschaleEntry` on `Client`, protobuf + XStream) · Plan 3 = `GermanTaxGainCalculator` realized-gains report · Plan 4 = UI (Taxes menu, two dialogs, CSV export). Plans 2–4 get their own documents when we reach them.

---

## File structure

- Create `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLot.java` — one surviving purchase lot (purchase transaction + remaining shares).
- Create `.../snapshot/vorabpauschale/OpenLotsResolver.java` — FIFO resolver for shares held as of a date.
- Create `.../snapshot/vorabpauschale/LotContribution.java` — per-lot line of the result.
- Create `.../snapshot/vorabpauschale/VorabpauschaleResult.java` — per-(security, year) result aggregate.
- Create `.../snapshot/vorabpauschale/VorabpauschaleCalculator.java` — the computation.
- Test `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLotsResolverTest.java`
- Test `.../snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java`

Run tests with (from repo root):
```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=<TestClass>
```

---

## Task 1: OpenLot value object

**Files:**
- Create: `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLot.java`

- [ ] **Step 1: Create the class**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import name.abuchen.portfolio.model.PortfolioTransaction;

/**
 * A surviving purchase lot as of a given date: the original purchase
 * transaction plus the number of shares (in {@link name.abuchen.portfolio.money.Values#Share}
 * precision) still held from it.
 */
public final class OpenLot
{
    private final PortfolioTransaction purchase;
    private final long shares;

    public OpenLot(PortfolioTransaction purchase, long shares)
    {
        this.purchase = purchase;
        this.shares = shares;
    }

    public PortfolioTransaction getPurchase()
    {
        return purchase;
    }

    public long getShares()
    {
        return shares;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLot.java
git commit -m "Add OpenLot value object for Vorabpauschale"
```

---

## Task 2: OpenLotsResolver (date-bounded FIFO)

**Files:**
- Create: `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLotsResolver.java`
- Test: `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLotsResolverTest.java`

Behavior: across all portfolios, treat BUY and DELIVERY_INBOUND as additions and SELL and DELIVERY_OUTBOUND as FIFO removals; ignore TRANSFER_IN/TRANSFER_OUT (investor-level no-ops that would otherwise overwrite the original acquisition date). Only transactions dated on/before `asOf` are considered. Returns surviving lots in FIFO order.

- [ ] **Step 1: Write the failing test**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class OpenLotsResolverTest
{
    private static final long SHARE = Values.Share.factor(); // 1 share

    @Test
    public void testFifoPartialSaleAndDateBound()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        // 10 sh @2023, 10 sh @2024-03, sell 12 in 2024-06, buy 5 in 2025
        new PortfolioBuilder() //
                        .buy(security, "2023-05-01", 10 * SHARE, 100_00) //
                        .buy(security, "2024-03-10", 10 * SHARE, 120_00) //
                        .sell(security, "2024-06-01", 12 * SHARE, 150_00) //
                        .buy(security, "2025-02-01", 5 * SHARE, 60_00) //
                        .addTo(client);

        // as of end of 2024: 10 + 10 - 12 = 8 shares held; FIFO leaves
        // 8 shares from the 2024-03-10 lot (2023 lot fully consumed)
        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, LocalDate.of(2024, 12, 31));

        assertThat(lots.size(), is(1));
        assertThat(lots.get(0).getShares(), is(8 * SHARE));
        assertThat(lots.get(0).getPurchase().getDateTime().toLocalDate(), is(LocalDate.of(2024, 3, 10)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `-Dtest=OpenLotsResolverTest`
Expected: FAIL — `OpenLotsResolver` does not exist (compilation error).

- [ ] **Step 3: Implement OpenLotsResolver**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.snapshot.trades.TradeCollector;

/**
 * Resolves the purchase lots of a security still held as of a given date,
 * aggregated across all of the client's portfolios. Buys and inbound
 * deliveries add shares; sells and outbound deliveries consume shares FIFO.
 * Transfers between the investor's own portfolios do not change the
 * investor-level holding and are ignored so that the original acquisition
 * date of each lot is preserved.
 */
public final class OpenLotsResolver
{
    private OpenLotsResolver()
    {
    }

    public static List<OpenLot> resolve(Client client, Security security, LocalDate asOf)
    {
        List<TransactionPair<?>> all = security.getTransactions(client);
        Collections.sort(all, TradeCollector.BY_DATE_AND_TYPE);

        Deque<long[]> open = new ArrayDeque<>(); // index into 'purchases' + remaining shares
        List<PortfolioTransaction> purchases = new ArrayList<>();

        for (TransactionPair<?> pair : all)
        {
            if (!(pair.getTransaction() instanceof PortfolioTransaction tx))
                continue;
            if (tx.getDateTime().toLocalDate().isAfter(asOf))
                continue;

            switch (tx.getType())
            {
                case BUY, DELIVERY_INBOUND:
                    purchases.add(tx);
                    open.addLast(new long[] { purchases.size() - 1, tx.getShares() });
                    break;
                case SELL, DELIVERY_OUTBOUND:
                    consume(open, tx.getShares());
                    break;
                case TRANSFER_IN, TRANSFER_OUT:
                    // investor-level no-op
                    break;
                default:
                    break;
            }
        }

        List<OpenLot> result = new ArrayList<>();
        for (long[] entry : open)
        {
            if (entry[1] > 0)
                result.add(new OpenLot(purchases.get((int) entry[0]), entry[1]));
        }
        return result;
    }

    private static void consume(Deque<long[]> open, long shares)
    {
        long remaining = shares;
        while (remaining > 0 && !open.isEmpty())
        {
            long[] lot = open.peekFirst();
            if (lot[1] <= remaining)
            {
                remaining -= lot[1];
                open.removeFirst();
            }
            else
            {
                lot[1] -= remaining;
                remaining = 0;
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `-Dtest=OpenLotsResolverTest`
Expected: PASS.

- [ ] **Step 5: Add a transfer-ignored test**

Append to `OpenLotsResolverTest`:

```java
    @Test
    public void testTransfersPreserveOriginalLot()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);

        // original purchase 2022; a portfolio transfer in 2024 must NOT
        // change the investor-level holding nor the acquisition date
        var source = new PortfolioBuilder().buy(security, "2022-01-10", 10 * SHARE, 100_00).addTo(client);
        var target = new PortfolioBuilder().addTo(client);
        source.transfer(security, "2024-05-01", 10 * SHARE, target, client);

        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, LocalDate.of(2024, 12, 31));

        assertThat(lots.size(), is(1));
        assertThat(lots.get(0).getShares(), is(10 * SHARE));
        assertThat(lots.get(0).getPurchase().getDateTime().toLocalDate(), is(LocalDate.of(2022, 1, 10)));
    }
```

Note: verify `PortfolioBuilder` exposes a transfer helper. Inspect it first:
`grep -n "transfer" name.abuchen.portfolio.junit/src/name/abuchen/portfolio/junit/PortfolioBuilder.java`.
If no transfer helper exists, build the transfer with `PortfolioTransferEntry` directly in the test (create entry, set source/target portfolios, security, date, shares, amount, then `entry.insert()`), matching the pattern used in existing trade tests (see `name.abuchen.portfolio.tests/.../snapshot/trades/TradeCollectorTest.java`).

- [ ] **Step 6: Run and commit**

Run: `-Dtest=OpenLotsResolverTest` → PASS.

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLotsResolver.java \
        name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/OpenLotsResolverTest.java
git commit -m "Add OpenLotsResolver with FIFO date-bounded holdings"
```

---

## Task 3: Result value objects

**Files:**
- Create: `.../snapshot/vorabpauschale/LotContribution.java`
- Create: `.../snapshot/vorabpauschale/VorabpauschaleResult.java`

- [ ] **Step 1: Create LotContribution**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.time.LocalDate;

import name.abuchen.portfolio.money.Money;

/** One purchase lot's contribution to a year's Vorabpauschale. */
public final class LotContribution
{
    private final LocalDate purchaseDate;
    private final long shares;
    private final Money baseValue;      // year-start NAV x shares, in EUR
    private final BigDecimal timeFactor; // 1, or (13 - purchaseMonth)/12
    private final Money basisertrag;    // baseValue x Basiszins x 0.70 x timeFactor
    private final Money contribution;   // this lot's share of the (post-cap, post-distribution) Vorabpauschale

    public LotContribution(LocalDate purchaseDate, long shares, Money baseValue, BigDecimal timeFactor,
                    Money basisertrag, Money contribution)
    {
        this.purchaseDate = purchaseDate;
        this.shares = shares;
        this.baseValue = baseValue;
        this.timeFactor = timeFactor;
        this.basisertrag = basisertrag;
        this.contribution = contribution;
    }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public long getShares() { return shares; }
    public Money getBaseValue() { return baseValue; }
    public BigDecimal getTimeFactor() { return timeFactor; }
    public Money getBasisertrag() { return basisertrag; }
    public Money getContribution() { return contribution; }
}
```

- [ ] **Step 2: Create VorabpauschaleResult**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Money;

/** Result of computing the Vorabpauschale for one security in one year. */
public final class VorabpauschaleResult
{
    private final Security security;
    private final int year;
    private final Money yearStartValue;
    private final Money distributions;
    private final Money grossBasisertrag;
    private final Money cappedBasisertrag;
    private final Money vorabpauschale;
    private final Money taxable;
    private final List<LotContribution> lots;
    private final List<String> warnings;

    public VorabpauschaleResult(Security security, int year, Money yearStartValue, Money distributions,
                    Money grossBasisertrag, Money cappedBasisertrag, Money vorabpauschale, Money taxable,
                    List<LotContribution> lots, List<String> warnings)
    {
        this.security = security;
        this.year = year;
        this.yearStartValue = yearStartValue;
        this.distributions = distributions;
        this.grossBasisertrag = grossBasisertrag;
        this.cappedBasisertrag = cappedBasisertrag;
        this.vorabpauschale = vorabpauschale;
        this.taxable = taxable;
        this.lots = Collections.unmodifiableList(new ArrayList<>(lots));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public Security getSecurity() { return security; }
    public int getYear() { return year; }
    public Money getYearStartValue() { return yearStartValue; }
    public Money getDistributions() { return distributions; }
    public Money getGrossBasisertrag() { return grossBasisertrag; }
    public Money getCappedBasisertrag() { return cappedBasisertrag; }
    public Money getVorabpauschale() { return vorabpauschale; }
    public Money getTaxable() { return taxable; }
    public List<LotContribution> getLots() { return lots; }
    public List<String> getWarnings() { return warnings; }
}
```

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/LotContribution.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleResult.java
git commit -m "Add Vorabpauschale result value objects"
```

---

## Task 4: VorabpauschaleCalculator — buy-and-hold base case

**Files:**
- Create: `.../snapshot/vorabpauschale/VorabpauschaleCalculator.java`
- Test: `.../snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java`

Signature: `compute(Client client, Security security, int year, BigDecimal basiszinsPercent, BigDecimal teilfreistellungFactor, CurrencyConverter converter) -> VorabpauschaleResult`.

`basiszinsPercent` is the raw published rate (e.g. `2.53` for 2.53 %). The calculator divides by 100 and multiplies by 0.70.

- [ ] **Step 1: Write the failing test**

Numbers: 1 EUR fund, held all of 2024, 100 shares. Year-start NAV 100.00, year-end 130.00. Basiszins 2.53 %, exemption 1.0, no distributions.
- baseValue = 100.00 × 100 = 10 000.00
- grossBasisertrag = 10 000.00 × 0.0253 × 0.70 = 177.10
- cap = 130.00×100 − 100.00×100 + 0 = 3 000.00 → not binding
- vorabpauschale = 177.10; taxable = 177.10

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

@SuppressWarnings("nls")
public class VorabpauschaleCalculatorTest
{
    private static final long SHARE = Values.Share.factor();
    private final TestCurrencyConverter eur = new TestCurrencyConverter(CurrencyUnit.EUR);

    @Test
    public void testBuyAndHoldFullYear()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getGrossBasisertrag(), is(Money.of("EUR", 177_10)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 177_10)));
        assertThat(r.getLots().size(), is(1));
    }
}
```

Note: confirm `Values.Quote.factorize(double)` exists (`grep -n "factorize" name.abuchen.portfolio/src/name/abuchen/portfolio/money/Values.java`). If the method is named differently, use the matching factorize helper; `SecurityBuilder.addPrice` takes a raw `long` in `Values.Quote` precision.

- [ ] **Step 2: Run test to verify it fails**

Run: `-Dtest=VorabpauschaleCalculatorTest`
Expected: FAIL — `VorabpauschaleCalculator` does not exist.

- [ ] **Step 3: Implement the calculator**

```java
package name.abuchen.portfolio.snapshot.vorabpauschale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;

/**
 * Computes the German Vorabpauschale (§ 18 InvStG) for one security in one
 * calendar year, with a per-lot breakdown. Pure computation: no persistence,
 * no UI. All monetary results are in the converter's term currency (EUR).
 */
public final class VorabpauschaleCalculator
{
    private static final BigDecimal BASISERTRAG_FACTOR = new BigDecimal("0.70");

    private VorabpauschaleCalculator()
    {
    }

    public static VorabpauschaleResult compute(Client client, Security security, int year,
                    BigDecimal basiszinsPercent, BigDecimal teilfreistellungFactor, CurrencyConverter converter)
    {
        String eur = converter.getTermCurrency();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        List<String> warnings = new ArrayList<>();

        List<OpenLot> lots = OpenLotsResolver.resolve(client, security, yearEnd);

        long totalShares = lots.stream().mapToLong(OpenLot::getShares).sum();
        if (lots.isEmpty() || totalShares == 0)
            return zero(security, year, eur, warnings);

        Money perShareStart = perShareInTermCurrency(security, yearStart, converter, warnings, "start");
        if (perShareStart == null)
            return zero(security, year, eur, warnings);

        BigDecimal basiszins = basiszinsPercent.movePointLeft(2); // 2.53 -> 0.0253

        // per-lot Basisertrag (kept as BigDecimal cents, unrounded until summed)
        List<BigDecimal> lotBasisertrag = new ArrayList<>();
        List<BigDecimal> lotBaseValue = new ArrayList<>();
        List<BigDecimal> lotTimeFactor = new ArrayList<>();
        BigDecimal grossCents = BigDecimal.ZERO;
        BigDecimal yearStartValueCents = BigDecimal.ZERO;

        for (OpenLot lot : lots)
        {
            BigDecimal sharesDec = BigDecimal.valueOf(lot.getShares()).movePointLeft(Values.Share.precision());
            BigDecimal baseValueCents = BigDecimal.valueOf(perShareStart.getAmount()).multiply(sharesDec);

            int pYear = lot.getPurchase().getDateTime().getYear();
            BigDecimal timeFactor;
            if (pYear < year)
            {
                timeFactor = BigDecimal.ONE;
            }
            else
            {
                int pMonth = lot.getPurchase().getDateTime().getMonthValue();
                timeFactor = BigDecimal.valueOf(13L - pMonth).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            }

            BigDecimal basisertrag = baseValueCents.multiply(basiszins).multiply(BASISERTRAG_FACTOR)
                            .multiply(timeFactor);

            lotBaseValue.add(baseValueCents);
            lotTimeFactor.add(timeFactor);
            lotBasisertrag.add(basisertrag);
            grossCents = grossCents.add(basisertrag);
            yearStartValueCents = yearStartValueCents.add(baseValueCents);
        }

        // distributions during the year (EUR)
        BigDecimal distributionsCents = BigDecimal.valueOf(distributions(client, security, year, converter).getAmount());

        // aggregate Wertsteigerung cap (skipped if year-end price missing)
        BigDecimal capCents = null;
        Money perShareEnd = perShareInTermCurrency(security, yearEnd, converter, warnings, "end");
        if (perShareEnd != null)
        {
            BigDecimal totalSharesDec = BigDecimal.valueOf(totalShares).movePointLeft(Values.Share.precision());
            BigDecimal yearEndValueCents = BigDecimal.valueOf(perShareEnd.getAmount()).multiply(totalSharesDec);
            capCents = yearEndValueCents.subtract(yearStartValueCents).add(distributionsCents).max(BigDecimal.ZERO);
        }

        BigDecimal cappedCents = capCents == null ? grossCents : grossCents.min(capCents);
        BigDecimal vorabCents = cappedCents.subtract(distributionsCents).max(BigDecimal.ZERO);
        BigDecimal taxableCents = vorabCents.multiply(teilfreistellungFactor);

        // per-lot contribution allocated in proportion to Basisertrag
        List<LotContribution> contributions = new ArrayList<>();
        for (int i = 0; i < lots.size(); i++)
        {
            BigDecimal share = grossCents.signum() == 0 ? BigDecimal.ZERO
                            : lotBasisertrag.get(i).divide(grossCents, 12, RoundingMode.HALF_UP);
            Money contribution = money(eur, vorabCents.multiply(share));
            contributions.add(new LotContribution(lots.get(i).getPurchase().getDateTime().toLocalDate(),
                            lots.get(i).getShares(), money(eur, lotBaseValue.get(i)), lotTimeFactor.get(i),
                            money(eur, lotBasisertrag.get(i)), contribution));
        }

        return new VorabpauschaleResult(security, year, money(eur, yearStartValueCents),
                        money(eur, distributionsCents), money(eur, grossCents), money(eur, cappedCents),
                        money(eur, vorabCents), money(eur, taxableCents), contributions, warnings);
    }

    private static Money perShareInTermCurrency(Security security, LocalDate date, CurrencyConverter converter,
                    List<String> warnings, String which)
    {
        SecurityPrice price = security.getSecurityPrice(date);
        if (price == null || price.getValue() == 0)
        {
            warnings.add("Missing year-" + which + " price for " + security.getName());
            return null;
        }
        long cents = BigDecimal.valueOf(price.getValue()).movePointLeft(Values.Quote.precisionDeltaToMoney())
                        .setScale(0, RoundingMode.HALF_UP).longValue();
        Money inSecurityCcy = Money.of(security.getCurrencyCode(), cents);
        return converter.convert(date, inSecurityCcy);
    }

    private static Money distributions(Client client, Security security, int year, CurrencyConverter converter)
    {
        String eur = converter.getTermCurrency();
        long cents = 0;
        for (TransactionPair<?> pair : security.getTransactions(client))
        {
            if (pair.getTransaction() instanceof AccountTransaction tx //
                            && tx.getType() == AccountTransaction.Type.DIVIDENDS //
                            && tx.getDateTime().getYear() == year)
            {
                cents += converter.convert(tx.getDateTime().toLocalDate(), tx.getGrossValue()).getAmount();
            }
        }
        return Money.of(eur, cents);
    }

    private static Money money(String currency, BigDecimal cents)
    {
        return Money.of(currency, cents.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static VorabpauschaleResult zero(Security security, int year, String eur, List<String> warnings)
    {
        Money z = Money.of(eur, 0);
        return new VorabpauschaleResult(security, year, z, z, z, z, z, z, new ArrayList<>(), warnings);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `-Dtest=VorabpauschaleCalculatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculator.java \
        name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java
git commit -m "Add VorabpauschaleCalculator with buy-and-hold base case"
```

---

## Task 5: Mid-year 1/12 reduction

**Files:**
- Test: `.../snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java` (add test)

Lot bought 2024-03 (March → `(13-3)/12 = 10/12`). 100 sh, year-start NAV 100.00, Basiszins 2.53 %, exemption 1.0, year-end 130.00 (cap not binding), no distributions.
- baseValue = 10 000.00; full basisertrag = 177.10; ×10/12 = 147.583… → **147.58**.

- [ ] **Step 1: Add the test**

```java
    @Test
    public void testMidYearPurchaseReducedByTwelfths()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2024-03-10", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        // 177.10 * 10/12 = 147.58
        assertThat(r.getGrossBasisertrag(), is(Money.of("EUR", 147_58)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 147_58)));
    }
```

- [ ] **Step 2: Run and confirm PASS** (implementation already handles this).

Run: `-Dtest=VorabpauschaleCalculatorTest#testMidYearPurchaseReducedByTwelfths`
Expected: PASS. If it fails on rounding, confirm expected value against `177.10 × 10 ÷ 12` rounded HALF_UP = 147.58.

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java
git commit -m "Test Vorabpauschale mid-year 1/12 reduction"
```

---

## Task 6: Distributions reduce the Vorabpauschale

**Files:**
- Test: `.../VorabpauschaleCalculatorTest.java` (add test)

Same buy-and-hold as Task 4 (gross 177.10) but with a 200.00 dividend in 2024. Since distributions (200.00) exceed Basisertrag (177.10), `vorabpauschale = max(0, 177.10 − 200.00) = 0`.

- [ ] **Step 1: Add the test**

```java
    @Test
    public void testDistributionsZeroOutVorabpauschale()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);
        new AccountBuilder("EUR").dividend("2024-07-01", 200_00, security, 100 * SHARE).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getDistributions(), is(Money.of("EUR", 200_00)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 0)));
    }
```

Note: confirm the `AccountBuilder` dividend signature before writing — run
`grep -n "dividend" name.abuchen.portfolio.junit/src/name/abuchen/portfolio/junit/AccountBuilder.java`.
Use the actual method (it may be `dividend(String date, long amount, Security security, long shares)` or similar); adapt the call to match. Add `import name.abuchen.portfolio.junit.AccountBuilder;`.

- [ ] **Step 2: Run and confirm PASS.**

Run: `-Dtest=VorabpauschaleCalculatorTest#testDistributionsZeroOutVorabpauschale`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java
git commit -m "Test Vorabpauschale distribution subtraction"
```

---

## Task 7: Gain cap in a down year

**Files:**
- Test: `.../VorabpauschaleCalculatorTest.java` (add test)

Buy-and-hold 100 sh; year-start NAV 100.00; **year-end 90.00** (fund fell). Cap = max(0, 9 000.00 − 10 000.00 + 0) = 0 → cappedBasisertrag = 0 → vorabpauschale = 0.

- [ ] **Step 1: Add the test**

```java
    @Test
    public void testDownYearCapsToZero()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(90.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getCappedBasisertrag(), is(Money.of("EUR", 0)));
        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
    }
```

- [ ] **Step 2: Run and confirm PASS.**

Run: `-Dtest=VorabpauschaleCalculatorTest#testDownYearCapsToZero`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java
git commit -m "Test Vorabpauschale gain cap in down year"
```

---

## Task 8: Teilfreistellung + multi-lot allocation

**Files:**
- Test: `.../VorabpauschaleCalculatorTest.java` (add test)

Two lots held all of 2024: 100 sh (2022) and 100 sh (2023); year-start NAV 100.00, year-end 130.00; Basiszins 2.53 %; **exemption 0.70** (equity ETF).
- gross = 200 sh × 100.00 × 0.0253 × 0.70 = 354.20; cap = 6 000.00 (not binding); vorab = 354.20; taxable = 354.20 × 0.70 = 247.94.
- Two equal lots → each contribution = 177.10.

- [ ] **Step 1: Add the test**

```java
    @Test
    public void testTeilfreistellungAndTwoLotAllocation()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-01-01", Values.Quote.factorize(100.00)) //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder() //
                        .buy(security, "2022-06-01", 100 * SHARE, 10_000_00) //
                        .buy(security, "2023-06-01", 100 * SHARE, 10_000_00) //
                        .addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), new BigDecimal("0.70"), eur);

        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 354_20)));
        assertThat(r.getTaxable(), is(Money.of("EUR", 247_94)));
        assertThat(r.getLots().size(), is(2));
        assertThat(r.getLots().get(0).getContribution(), is(Money.of("EUR", 177_10)));
        assertThat(r.getLots().get(1).getContribution(), is(Money.of("EUR", 177_10)));
    }
```

- [ ] **Step 2: Run and confirm PASS.**

Run: `-Dtest=VorabpauschaleCalculatorTest#testTeilfreistellungAndTwoLotAllocation`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java
git commit -m "Test Vorabpauschale Teilfreistellung and multi-lot allocation"
```

---

## Task 9: Missing year-start price yields a warning, not a crash

**Files:**
- Test: `.../VorabpauschaleCalculatorTest.java` (add test)

Holdings exist but the security has no Jan-1 price → result is zero with a warning.

- [ ] **Step 1: Add the test**

```java
    @Test
    public void testMissingYearStartPriceWarns()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR") //
                        .addPrice("2024-12-31", Values.Quote.factorize(130.00)) //
                        .addTo(client);
        new PortfolioBuilder().buy(security, "2023-06-01", 100 * SHARE, 10_000_00).addTo(client);

        VorabpauschaleResult r = VorabpauschaleCalculator.compute(client, security, 2024,
                        new BigDecimal("2.53"), BigDecimal.ONE, eur);

        assertThat(r.getVorabpauschale(), is(Money.of("EUR", 0)));
        assertThat(r.getWarnings().isEmpty(), is(false));
    }
```

Note: `Security.getSecurityPrice` returns the nearest available price rather than null when *any* price exists. With only a 2024-12-31 price, a Jan-1 lookup returns that price, so this test may need the security to have **no** prices at all to force the missing-price path, or the calculator's missing-price detection must treat "no price on/before the requested date" as missing. Before implementing, verify `getSecurityPrice` behavior (`sed -n '533,600p' name.abuchen.portfolio/src/name/abuchen/portfolio/model/Security.java`) and, if it never returns null when prices exist, either (a) use a security with zero prices in this test, or (b) strengthen `perShareInTermCurrency` to require a price dated on/before `date` (and treat a later-only price as missing). Pick (b) if the year-start price genuinely must precede/equal Jan-1; document the choice in the commit.

- [ ] **Step 2: Run and confirm PASS** (adjusting per the note).

- [ ] **Step 3: Commit**

```bash
git add name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculatorTest.java \
        name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/vorabpauschale/VorabpauschaleCalculator.java
git commit -m "Handle missing year-start price with a warning"
```

---

## Task 10: Full regression run

- [ ] **Step 1: Run both test classes together**

```bash
mvn -f portfolio-app/pom.xml verify -Plocal-dev -o \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd \
  -Dtest=OpenLotsResolverTest,VorabpauschaleCalculatorTest
```
Expected: all green.

- [ ] **Step 2: Confirm no other tests broke** (the package is new and isolated; a broader run is optional at this stage).

---

## Self-review checklist (done while writing)

- **Spec coverage:** formula (§3), mid-year 1/12, distributions, gain cap, Teilfreistellung, per-lot allocation, EUR conversion, missing-quote warning — all covered by Tasks 4–9. Persistence, gains report, and UI are explicitly deferred to Plans 2–4.
- **Type consistency:** `compute(...)` signature, `VorabpauschaleResult`/`LotContribution` getters, and `OpenLot`/`OpenLotsResolver` names are used identically across tasks.
- **Known verification points flagged inline:** `PortfolioBuilder.transfer` helper (Task 2 Step 5), `Values.Quote.factorize` (Task 4), `AccountBuilder.dividend` signature (Task 6), and `Security.getSecurityPrice` null behavior (Task 9). Each task says how to adapt if the API differs.
