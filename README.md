# ledger-sync

A ledger built from bank SMS/email, an incident fixed, and a migration off SQL onto MongoDB.

This file is my submission write-up. The original assignment spec that shipped
with the seed repo is preserved at [`SPEC.md`](SPEC.md) for reference.

## Running it (under 5 minutes)

### Task 2/3: parse, dedup, categorise, reconcile - no Docker needed

```
./verify.sh
```

Pure JDK, no network, no database. Compiles the whole main tree except
`store/mongo` (see "Why `store/mongo` is excluded from `verify.sh`" below) and
runs the full pipeline against `fixtures/corpus-a.jsonl` through an in-memory
store, printing ingest stats, category totals, the check against
`corpus-a-totals.json`, and reconciliation. This is also what CI runs
(`.github/workflows/verify.yml`).

### Task 4: move it onto MongoDB

```
docker compose up -d          # single-node Mongo replica set - see why below
./gradlew run --args=migrate
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report out"          # ledger.json, summary.json, reconciliation.json, from SQL
./gradlew run --args=backfill              # move everything in SQL onto MongoDB
./gradlew run --args=check                 # confirm the two stores agree
./gradlew run --args=bench                 # seed 100k txns, print examined/returned for Q1-Q3
```

`./gradlew test` runs the JUnit suite, including `NormalizedTxnContractTest`
and `IncidentINC20260911Test`.

### Why `store/mongo` is excluded from `verify.sh`

`MongoDocumentStore.java` imports the MongoDB driver's classes directly
(`com.mongodb.client...`) - there is no JDK-native document-store API to load
it reflectively through, unlike `SqlLedgerStore`, which only touches
`java.sql.*` and loads the H2 driver via `DriverManager` at runtime. So the
Mongo driver has to be a real compile-time dependency (`build.gradle`), and
`verify.sh`'s whole premise ("no network, no database, no Gradle") breaks if
any file it compiles needs that jar present. `store/mongo/` is isolated into
its own package specifically so `verify.sh` can exclude just that one
directory with a single `find ... -not -path` and keep everything else -
including `Backfill` and `ConsistencyChecker`, which are storage-agnostic -
fully covered by the dependency-free build. `App.java` reaches
`MongoDocumentStore` through reflection for the same reason (see its javadoc).

## What's here

- `incident/INC-2026-09-11.md` - the incident, reproduced, fixed, and closed
  out at the bottom of that file (root cause, blast radius, the five-line
  summary).
- `src/main/java/.../parse` - message parsing, one class per shape (HDFC SMS,
  ICICI SMS, transaction-alert email).
- `src/main/java/.../ingest/TransactionAssembler.java` - dedup and
  categorisation; the single most important file in this submission for
  understanding *why* the numbers come out the way they do.
- `src/main/java/.../report/Reports.java` - the three output files, including
  balance-continuity reconciliation.
- `src/main/java/.../store` - `SqlLedgerStore` (Task 2/3), `Backfill` and
  `ConsistencyChecker` (Task 4, storage-agnostic), `store/mongo/` (the one
  concrete document store).

## Decision log

Ten places I had to choose, in the order they came up. The interesting ones
(marked \*) are where I was not sure, or where the data changed my mind.

1. **Amounts.first() bug fix: make the decimal suffix optional, not add a
   fallback heuristic.** The incident's root cause was `\.[0-9]{2}` being
   required in the amount regex. I considered "if no match, try again without
   the decimal requirement" as a second pass, but that's the same regex with
   extra steps and extra surface for bugs. Making the suffix `(?:\.[0-9]{2})?`
   is the direct fix and `toDecimal()` already `.setScale(2)`s the result, so
   nothing downstream needed to change.

2. **\*Dedup key is (account, direction, amount, occurredAt) - deliberately
   not merchant.** SMS and email report the same transaction with what is
   usually identical merchant text, but I did not want dedup to *depend* on
   that holding exactly, since a bank could easily abbreviate differently
   across channels. The four fields above are what actually identifies a
   transaction; merchant is evidence about it, not part of its identity. Where
   two pieces of evidence disagree on merchant text, `TransactionAssembler`
   picks deterministically (prefers a channel-prefixed string, then longer,
   then lexicographically smaller) rather than depending on ingest order -
   important for `LedgerStore.save()`'s idempotency guarantee to actually
   hold across reruns.

3. **\*Email `Date:` headers get normalised to IST before anything else
   touches them.** One email in the whole corpus (`m-00131`, an Uber charge)
   uses a UTC offset instead of IST like all 55 others. Before I normalised
   it, dedup treated the SMS and the email as two different transactions
   (same instant, different string), and `verify.sh`'s numbers were off by
   exactly one transaction and Rs.412.67. `Dates.toIst()` fixes this once,
   centrally, rather than special-casing the email parser.

4. **\*MICRO detection is "merchant starts with UPI", not "starts with
   UPI/".** I initially required the slash (`UPI/WATER CAN` etc., which is
   the overwhelmingly common shape). Account 9075 has a UPI mandate
   verification debit written as `UPI MANDATE VERIFY` - no slash - and it was
   landing in SPEND instead of MICRO, which showed up as a Rs.0.50 / 1-count
   discrepancy against `corpus-a-totals.json`. Broadening the check to a bare
   prefix fixed it and cannot false-positive here: every non-UPI merchant in
   the corpus (BIGBASKET, SPOTIFY, RELIANCE SMART, ...) starts with something
   else entirely.

5. **\*TRANSFER is decided by finding the matching leg, never by a keyword in
   the merchant field.** I checked this by hand before writing any pairing
   code: `IMPS/P2A/PARAG KAPOOR` appears as a debit on one tracked account and
   a credit on the other, same amount, minutes apart, every single time - and
   the *amounts* sum exactly to `corpus-a-totals.json`'s transferred_out/in
   figures. `NEFT INWARD SELF` (an 18,000 credit, twice) and
   `IMPS/P2A/RAHUL SHARMA` (a 12,000 debit) both *look* like transfers by
   name, but neither has a matching leg anywhere in the corpus - there is no
   account of ours on the other side of them. `TransactionAssembler` pairs
   opposite-direction, same-amount, same-counterparty-suffix legs across
   accounts within a 6-hour window instead of pattern-matching names, so it
   gets both cases right without hardcoding either label.

6. **\*The Uber SMS+email pair is one transaction (145, not 146, on 4821) -
   and I kept it that way rather than force-matching the checkpoint.** Once
   fix #3 is in place, this pair merges. `corpus-a-totals.json` says 4821 has
   146 transactions; correctly merged, it has 145. I checked hard before
   trusting my own logic over the checkpoint: there is no other Rs.412.67
   transaction anywhere in the corpus, the merchant matches, and the instant
   matches exactly once the UTC offset is converted. I am confident this is
   one real transaction reported on two channels, and that the checkpoint
   simply was not built to expect a non-IST email offset. Reporting 145 and
   explaining why, rather than quietly declining to fix #3 so the count would
   match, is the point of "your numbers are honest" in the brief.

7. **\*The Rs.7,500 gap on 4821 is reported, not invented.** Balance
   reconciliation (walking each account's SMS-quoted `Avl Bal` in order) finds
   a Rs.7,500 debit that no message in the corpus evidences at all - the
   balance drops by exactly that much between two consecutive SMS with
   nothing in between. This is almost certainly deliberate corpus design (see
   `incident/INC-2026-09-11.md`'s resolution for the equivalent situation with
   the water-can bug). `reconciliation.json` reports it; `ledger.json` does
   not invent a transaction with no `source_message_ids` to back it, because
   non-negotiable #6 ("a transaction is traceable... from its own records")
   would be a lie if it did.

8. **\*Reconciliation only runs on accounts where the balance field is
   actually a running balance.** I built the continuity check against 4821
   and 9075 first, then ran it against the credit card (3310) and got 20
   "discrepancies" - one on almost every transaction. `Avl Limit` on a credit
   card is not a monotonic running total the way a savings account's `Avl
   Bal` is: it moves for reasons a spend message never mentions (payments,
   holds, statement cycles). `Amounts.statedBalance()` deliberately does not
   match `Avl Limit` at all, so `BalanceSnapshot`s are never produced for the
   card and this is not a special case in `Reports.reconciliation()` - it
   just never sees card data to begin with.

9. **MongoDB over DynamoDB.** Both are reasonable; I chose Mongo because (a)
   the document model below serves all three queries off plain
   compound/multikey indexes with no secondary-index read-amplification to
   reason about, (b) `explain()` gives `totalDocsExamined`/`nReturned`
   directly with no extra instrumentation, and (c) local dev is one
   `docker-compose` service (a single-node replica set, needed for `save()`'s
   transaction) versus DynamoDB Local's table/capacity provisioning
   boilerplate. Either would have satisfied the assignment; this was a
   toolchain-friction call more than a data-modelling one.

10. **`LedgerStore.save()`'s contract changed from "may create duplicates" to
    "idempotent by natural key."** The seed's original `LedgerStore` javadoc
    said saving the same transaction twice was not guaranteed to produce one
    row. I redefined this (the interface is not frozen) because it is the
    only way `IngestService` can be re-run against an overlapping corpus and
    leave the ledger unchanged, which the brief requires as a non-negotiable.
    `DocumentStore.save()` mirrors the same contract for the same reason.

## What the data made you decide

Beyond the decision log above, things the corpus itself forced, with no
instruction telling me what to do about them:

- **A third account exists that the checkpoint never mentions.**
  `fixtures/corpus-a-totals.json` only gives per-account figures for 4821 and
  9075, but the corpus also contains 34 raw "HDFC Bank Card x3310" messages
  (20 distinct, after dedup). `257 - 146 - 91 = 20` - the checkpoint's total
  transaction count *does* account for the card, just without breaking it out
  per-account. I treat 3310 as a normal account throughout; it just has no
  checkpoint to validate spend/income against.
- **E-mandate previews are not transactions.** "`E-mandate! Rs.649.00 will be
  deducted... on 22-07-26 at 06:15`" is future-tense - a pre-authorisation
  notice, not a debit. The *actual* debit for the same amount, same time, is
  a separate email. Only the confirmed debit is counted; the preview
  correctly falls into `messages skipped`.
- **A second ICICI SMS shape existed and was silently dropped before I
  touched it** (the seed's `IciciSmsParser` only read the `Dear Customer...`
  shape). 104 of 161 ICICI SMS use the `ICICI Bank Acct XX... Dr/Cr INR...
  ref no...` shape instead. Both are implemented now.
- **Marketing SMS share a sender with real transaction alerts.** ICICI's
  personal-loan ads come from the same `VM-ICICIB-T` sender as real debit/
  credit SMS. They are excluded by shape (no structural match), not by
  content-sniffing for "loan" or similar - a content filter would be a much
  easier thing for a slightly different ad to slip past.
- **A hostile sender exists specifically to be excluded by sender ID, not
  content.** `VK-ICICIB` (one character off from the real `VM-ICICIB-T`)
  sends a phishing SMS shaped to look similar. `IciciSmsParser.supports()`
  checks the exact sender string, so this never reaches the parsing logic at
  all - it is not a message we almost mis-parse, it is a message we never
  attempt.

## Document model

Two collections in a `ledgersync` database.

**`transactions`** - one document per real transaction:

```json
{
  "_id": "4821|DEBIT|5.00|2026-07-04T07:19:00+05:30",
  "account_last4": "4821",
  "year_month": "2026-07",
  "occurred_at": "2026-07-04T07:19:00+05:30",
  "direction": "DEBIT",
  "amount": {"$numberDecimal": "5.00"},
  "category": "MICRO",
  "merchant": "UPI/WATER CAN",
  "source_message_ids": ["m-00004-9c11ae"]
}
```

`_id` *is* the natural key, so `save()` is a plain upsert - no duplicate can
ever be created, by construction, even under concurrent or repeated calls.
`year_month` is redundant with `occurred_at` and exists purely so Q1 is a
single equality+equality+sort index scan with no in-memory date filtering.

Indexes: `{account_last4: 1, year_month: 1, occurred_at: -1}` for Q1;
`{source_message_ids: 1}` (multikey - Mongo indexes each array element) for
Q3.

**`account_category_totals`** - one document per account:

```json
{
  "_id": "4821",
  "totals": {
    "SPEND": {"$numberDecimal": "79568.38"},
    "INCOME": {"$numberDecimal": "101340.83"},
    "MICRO": {"$numberDecimal": "2357.51"},
    "TRANSFER": {"$numberDecimal": "31000.00"}
  }
}
```

This is a materialised view, not an aggregation target. Q2 asks for a
whole-history running total; summing every transaction on every read does
not scale, so `save()` keeps this document's four counters correct as it
goes (see `MongoDocumentStore.save()`'s javadoc for exactly how it stays
correct under retries and re-categorisation). Q2 becomes a single point read
on `_id`, which Mongo always indexes.

### The six numbers

I could not produce these as real measurements: this environment has no
Docker daemon and no network path to Maven Central for the MongoDB driver
jar (see "What's unfinished"). `Bench.java` is written and will print real
numbers from `./gradlew run --args=bench` once `docker compose up -d` has a
replica set running - please run it; I'd rather say that plainly than invent
numbers. What I can say with confidence, from the index design and
`explain()`'s documented behaviour, not from a measurement:

| Query | Expected examined | Expected returned |
|---|---|---|
| Q1 `forAccountMonth` | approx. the rows in that account+month (the compound index makes this an index scan, not a collection scan - examined tracks the *matching* set, not all 100k) | same |
| Q2 `categoryTotals` | 1 (point read on `_id`) | 1 |
| Q3 `byMessageId` | 1 (message ids are unique; the multikey index resolves the array-contains lookup directly) | 1 |

If `bench` reports anything other than examined is approximately returned for
Q1, or anything other than 1/1 for Q2 and Q3, that means an index is missing
or the query planner is not using it, and the six numbers in this table are
wrong - that discrepancy is itself the useful signal, not something to paper
over.

## AI disclosure

Claude (Anthropic) did the great majority of the analysis, coding, and
debugging in this submission, working from the assignment brief and the seed
repo. Concretely: all parsing/dedup/categorisation logic, the incident fix
and its tests, the document store design, `Backfill`/`ConsistencyChecker`,
and this write-up.

One concrete case where it was wrong first, and the difference mattered:

**Before** - the first version of `EmailParser` parsed the `Date:` header
straight into `occurredAt` with no timezone handling:

```java
at = OffsetDateTime.parse(d.group("date").trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
```

This is wrong for exactly one email in the corpus (`m-00131`, `Date: Sat, 18
Jul 2026 18:50:00 +0000`), which is intentionally the only one using UTC
instead of IST. The bug did not throw or look wrong in any obvious way - it
just silently produced an `OffsetDateTime` in the wrong offset, which made
`TransactionAssembler`'s natural-key dedup treat that email and its matching
SMS as two different transactions instead of one. `verify.sh`'s output
looked *plausible* (256 vs 257 expected is a very small, easy-to-shrug-off
gap) and it took a deliberate cross-check against an independent Python
re-implementation of the parsing logic to actually catch it, rather than
"the numbers look about right."

**After**:

```java
at = Dates.toIst(OffsetDateTime.parse(d.group("date").trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
```

with `Dates.toIst()` doing `dt.withOffsetSameInstant(IST)`. The difference is
one instant-preserving conversion, but the failure mode it fixes - a silent,
plausible-looking off-by-one in a financial ledger - is exactly the class of
bug this whole assignment is about (see the incident). I would not have
caught this from re-reading the code; I caught it by building a second,
independent implementation and finding where the two disagreed, which is the
same lesson INC-2026-09-11 teaches about the original test suite passing
throughout.

## What's unfinished

- **Task 0 and Task 1** need a human with the actual Simplify Money app - not
  something I can do. Not attempted here.
- **MongoDocumentStore is compiled but not run.** No Docker daemon and no
  network path to Maven Central in this environment meant I could not bring up
  Mongo or pull the driver jar to test against a real instance. I hand-built a
  method-signature-accurate stub of the driver's sync API and compiled the
  real class against it (catches wrong method names/argument types, not
  runtime behaviour), and validated `Backfill`/`ConsistencyChecker` - the code
  that actually has to be correct - against a real H2-backed SQL store and a
  fake in-memory `DocumentStore` that honours the same contract. That is real
  evidence the migration logic works; it is not the same as having run it
  against Mongo, and I want to be upfront about that gap rather than imply I
  verified something I didn't.
- **The six examined/returned numbers are expected, not measured** - see
  above. `./gradlew run --args=bench` will produce real ones.
- **`m-legacy-0041`, the legacy water-can row, is migrated as-is, still
  wrong.** Backfill does not attempt to correct it - see the decision log in
  `store/Backfill.java`'s javadoc for why fabricating a corrected amount would
  be worse than an honestly-wrong, clearly-flagged one. Recovering the true
  amount needs the original SMS, which the SQL table does not retain.
- **No walkthrough recording.** I can't record video/screen capture from this
  environment.
- **No deployed URL** - not required per the brief, and hosting costs money
  for a take-home.
