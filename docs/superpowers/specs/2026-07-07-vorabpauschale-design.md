# Vorabpauschale for International-Broker Holdings — Design

Date: 2026-07-07
Branch: `feature/vorabpauschale` (off `master`)

## 1. Motivation

German investors owe **Vorabpauschale** — a yearly advance lump-sum tax on the
notional (unrealized) gains of accumulating funds/ETFs. Domestic brokers compute
and withhold it automatically. After moving to an international broker (IBKR),
the user must compute it themselves and account for it at sale time, because
Vorabpauschale already paid raises the cost basis of the affected lots and must
not be taxed again as a realized gain.

This feature adds two capabilities to Portfolio Performance, delivered on one
branch off `master`:

- **A. Yearly Vorabpauschale tool** — a dialog to compute the Vorabpauschale for
  a chosen year, review a per-security and per-lot breakdown, export CSV, and
  *finalize* the year into a persistent ledger.
- **B. German realized-gains report** — a dialog/report that, for a chosen year,
  FIFO-consumes purchase lots for each sale, adds each consumed lot's
  *accumulated* Vorabpauschale to its cost basis, and reports the taxable gain
  after Teilfreistellung. Exportable to CSV.

## 2. Legal basis (§ 18 InvStG)

Verified against the statute
([§ 18 InvStG](https://www.gesetze-im-internet.de/invstg_2018/__18.html)).

Key points that shape the design:

- **Basisertrag = year-start redemption price × Basiszins × 70 %.** The 70 % is
  applied to *every* fund type; it is part of the Basisertrag formula and is
  distinct from the Teilfreistellung.
- **Mid-year lots use the fund's year-start NAV**, not the purchase price. A lot
  bought during the year still references the same Jan-1 fund price; the
  acquisition is handled *only* by a time reduction (§ 18 Abs. 3): the
  Vorabpauschale is reduced by 1/12 for each full month preceding the purchase
  month. The purchase price matters later, for the gain-at-sale computation.
- **Distributions reduce it:** `Vorabpauschale = max(0, Basisertrag − Ausschüttungen)`.
- **Gain cap (Wertsteigerung):** the Basisertrag is capped at the actual value
  increase over the year `(yearEndPrice − yearStartPrice + distributions)`, and
  floored at 0 if the fund lost value.
- **Teilfreistellung (§ 20):** for equity funds 30 % of investment income is
  exempt (factor 0.7), mixed funds 15 % (factor 0.85), real-estate funds 60 %
  (factor 0.4) or 80 % for predominantly foreign real estate (factor 0.2),
  otherwise 1.0. The value is stored as a per-security percent attribute, so any
  factor can be entered.

## 3. Formula (per security, per year)

```
yearStartNAV  = quote on first day of year        → converted to EUR
yearEndNAV    = quote on last day of year          → EUR
distributions = Σ dividends for the security in the year (EUR)

for each purchase lot held during the year:
    baseValue_i   = yearStartNAV_perShare × shares_i
    timeFactor_i  = heldSinceBeforeYear ? 1 : (13 − purchaseMonth)/12
    basisertrag_i = baseValue_i × Basiszins × 0.70 × timeFactor_i    # 0.70 always

grossBasisertrag  = Σ basisertrag_i
cap               = max(0, yearEndValue − yearStartValue + distributions)  # asset-aggregate
cappedBasisertrag = min(grossBasisertrag, cap)
vorabpauschale    = max(0, cappedBasisertrag − distributions)
taxable           = vorabpauschale × teilfreistellungFactor    # 0.7 equity / 0.85 mixed / 0.4 RE / 1.0 other
```

Per-lot contribution to `vorabpauschale` is allocated in proportion to
`basisertrag_i`. That allocation is what accumulates onto each purchase
transaction across the years it is held.

### Decided parameters

- **Basiszins input:** the user enters the *raw* Bundesbank Basiszins (e.g.
  2.53 % for 2025). PP applies the × 0.70 itself. A zero/negative Basiszins year
  yields a Vorabpauschale of 0.
- **Gain cap granularity:** applied at the **security-aggregate** level (not
  per-lot). Per-lot capping is stricter and rarely relevant for retail.
- **Currency:** all monetary results are in EUR, converted at the relevant date
  via `CurrencyConverter`.

## 4. Architecture

### 4.1 Core computation — `name.abuchen.portfolio`, new package `snapshot/vorabpauschale/`

- **`VorabpauschaleCalculator`** — pure computation for one
  `(security, year, basiszins, exemptionFactor)`. Uses `TradeCollector` to
  obtain the lots held during the year, `Security` quotes for year-start/end
  NAVs, dividend transactions for distributions, and `CurrencyConverter` for
  EUR conversion. No UI or persistence dependencies.
- **`VorabpauschaleResult`** — security-level totals (yearStartValue,
  distributions, grossBasisertrag, cappedBasisertrag, vorabpauschale, taxable)
  plus a list of `LotContribution`.
- **`LotContribution`** — `{ purchaseTxUUID, purchaseDate, purchaseMonth,
  shares, baseValue, timeFactor, contribution }`.
- **`GermanTaxGainCalculator`** — for a chosen year: for each SELL transaction,
  FIFO-consume purchase lots (reusing `TradeCollector`/`Trade`), sum the
  accumulated Vorabpauschale for each consumed lot from the ledger (allocation
  re-derived per year in proportion to `basisertrag_i`, prorated by the fraction
  of the lot sold), and compute the taxable gain after Teilfreistellung.

### 4.2 Persistence — `name.abuchen.portfolio/model/`

- **`VorabpauschaleEntry`** — first-class domain object at **(security, year)**
  granularity: `{ securityUUID, year, basiszins, exemptionFactor,
  yearStartValue€, distributions€, cappedBasisertrag€, vorabpauschale€,
  taxable€, finalizedAt }`.
  Per-lot splits are **derived on demand**, never stored — PP has no stable lot
  identity (lots are recomputed by `TradeCollector` FIFO each time), whereas
  purchase transactions have stable UUIDs. Storing the year-start NAV, Basiszins
  and exemption factor makes recomputation of the per-lot allocation
  deterministic and stable against later quote revisions.
- **`Client`** gains `List<VorabpauschaleEntry>` with getters/adders, mirroring
  `getDashboards()` / `getPlans()`.
- **`client.proto`** — add `PVorabpauschaleEntry`, `repeated` in `PClient`;
  read/write wired in `ProtobufWriter`.
- **`ClientFactory`** — XStream alias + immutable-type registration; bump schema
  version with a no-op migration. Old files load with an empty ledger (fully
  backward compatible; the new protobuf field is optional).

### 4.3 Teilfreistellung configuration

- Register a default **percent `AttributeType` "Teilfreistellung"** applicable
  to `Security`. `VorabpauschaleCalculator` reads it as the per-security default;
  the dialog allows a per-run override.

### 4.4 UI — `name.abuchen.portfolio.ui`

- A new **Taxes submenu** in the main menu bar hosting both tools.
- **`OpenVorabpauschaleDialogHandler`** → **Vorabpauschale dialog**: year
  selector, Basiszins field, holdings table (security · exemption [editable] ·
  year-start NAV · distributions · gross Basisertrag · Vorabpauschale · taxable)
  with an expandable per-lot breakdown (month bought, time factor, contribution).
  Buttons: **Recompute**, **Export CSV**, **Finalize year** (writes ledger
  entries for the year).
- **German gains report dialog** — same shape for part B: per-sale rows with the
  consumed FIFO lots, purchase cost, accumulated Vorabpauschale, and taxable
  gain after Teilfreistellung.
- **`CSVExporter.exportVorabpauschale(...)`** and
  **`exportGermanTaxGains(...)`**, following the existing exporter conventions.
  Vorabpauschale CSV columns: asset, ISIN, month bought?, Teilfreistellung,
  year-start value, gross Basisertrag, distributions, Vorabpauschale, taxable.

## 5. Testing

- **Core unit tests** for `VorabpauschaleCalculator`: full-year hold; mid-year
  purchase `(13 − month)/12`; distributing fund → 0; down year → cap 0; multi-lot
  allocation; non-EUR fund with currency conversion.
- **Gains tests** for `GermanTaxGainCalculator`: FIFO partial sale; accumulated
  Vorabpauschale raises the basis; Teilfreistellung applied to the gain.
- **Persistence round-trip** (protobuf *and* XML), including an old file with no
  ledger loading cleanly.
- Follow PP test conventions (JUnit, existing matcher helpers where applicable).

## 6. Change surface & risks

- **Core persistence changes** (`client.proto`, `ProtobufWriter`,
  `ClientFactory`, schema-version bump) are the highest-risk area. Backward
  compatibility is a hard requirement: existing `.portfolio` files must load with
  an empty ledger.
- **Gain-cap simplification** to security-aggregate is a deliberate, documented
  deviation from strict per-lot capping.
- **Missing quotes** (no year-start or year-end price) must be surfaced clearly
  in the breakdown rather than silently producing wrong numbers.

## 7. Out of scope (v1)

- Automatic booking of Vorabpauschale as account/cash transactions (the ledger is
  intentionally cash-neutral; the user pays via annual tax return).
- Automatic retrieval of the official Basiszins (entered manually).
- Non-German tax regimes.
