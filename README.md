# tequipy — Equipment Allocator

Spring Boot 4 / Kotlin / PostgreSQL service that allocates a bundle of equipment to an
employee given a slot-based policy. Allocation is asynchronous: the API accepts a request
(`202 Accepted`), an in-process listener runs the matching algorithm against the inventory
under a pessimistic lock, and the request transitions `PENDING → RESERVED` (or `FAILED`).
The reservation can later be `confirmed` (equipment moves to `ASSIGNED`) or `cancelled`
(equipment goes back to `AVAILABLE`).

# What's not done 
1. Replacing in-process event bus with broker
   Current implementation is not sufficient for production, since it's not resilient for random crashes. That being said, introducing a message broker  
   adds complexity that may not necessarily be needed. I would consider transactional outbox based with postgres. With correctly used FOR UPDATE it can 
   provide great performance and guarantees needed for production system.
   (Quick workaround could be scheduled job that picks up allocations older then couple of minutes and retries processing)
2. Security - assuming this service wouldn't be exposed directly to outside world it was ignored. 
   Could be solved by exposing it via gateway, or using service mesh to add security.
3. Observability: 
- Structured logging (JSON, MDC trace/span IDs) — currently plain text.
- Distributed tracing. Spring Boot 4 ships Micrometer Tracing - wiring an OTLP exporter is a few lines.
- Domain metrics. Allocation throughput, p50/p99 of the matching call (we benchmark it offline but don't measure prod), PENDING → RESERVED lag, FAILED reason cardinality, equipment-pool size by status.
- Alerts on the things that matter. Async queue depth, processing lag (p99 of now() − createdAt for PENDING), 5xx rate, DB lock wait time
4. Validation & contract
- Per-policy size cap. slots.size and count need an explicit max — the algorithm complexity is bounded by them.
- Wire-level constraints on enums via @Schema / @Pattern so the OpenAPI spec encodes them and clients get useful 400 messages.
- @CreatedBy / actor on every state transition for auditability (currently we don't even know who confirmed an allocation).
5. Build & release
- CI pipeline - dependant on whatever is already setup (couold be github actions)
- Versioning + release artifact (Docker image with a SHA tag, optional Maven publish for the OpenAPI spec).
- Migration safety check in CI — Flyway plan against a snapshot of prod schema before merging.
- Reproducible builds — pin Gradle wrapper SHA, lock dependency versions (Gradle's --write-locks), pin the Postgres image digest.

# Design choices

### Async allocation with `202 Accepted`

`POST /allocations` returns immediately with state `PENDING`. The matching algorithm runs
on a separate thread, transitions the request to `RESERVED` (or `FAILED`), and the client
polls `GET /allocations/{id}` for the outcome. This decouples the slow path (Hungarian on
a large pool) from the request thread, keeps the API responsive, and gives us a natural
place to add per-job retries, dead-lettering, and a future external broker without
changing the API contract.

### Pessimistic lock on the equipment pool, not optimistic on the allocation

When the listener picks up an `AllocationCreated` event, it loads candidate equipment with
`SELECT … FOR UPDATE` filtered by `status = AVAILABLE` and the requested types. Two
concurrent allocations for the same scarce equipment serialise on Postgres row locks; one
wins and reserves, the other re-reads after lock release, sees the equipment now `RESERVED`,
and fails cleanly. The alternative — letting both proceed and resolving the conflict via
the `unique(allocation_equipment.equipment_id)` index — would force ugly retry logic. The
pessimistic lock is only held for the duration of the matching call (low milliseconds);
the cost is acceptable given the rarity of true contention.

### Transactional outbox

`CreateAllocationUseCase` writes both the new `AllocationRequest` *and* an `outbox` row in
the same transaction. The in-process `@TransactionalEventListener(AFTER_COMMIT)` is the
cheap, low-latency path; the outbox is the durable trail. When the broker is wired in
(see *What's not done*), it will read from the outbox table, not from the in-memory event
bus, so messages survive crashes between commit and publish. Doing this from day one
costs almost nothing and avoids a future schema migration.

### State-based + key-based idempotency

Confirm and cancel are state transitions (`RESERVED → CONFIRMED`, `*ACTIVE → CANCELLED`),
so they're naturally idempotent for repeated calls on the *same* terminal state — calling
`confirm` on a `CONFIRMED` allocation is a no-op `200`. On top of that, an optional
`Idempotency-Key` header records the (key, operation, allocationId) tuple in
`idempotency_key`. A replayed request with the same key returns the same response; the
same key reused on a *different* allocation returns `409`. This handles the "client
retried the request because the network ate the response" case without us having to
implement Stripe-grade response caching.

### JPA over Spring Data JDBC

The first cut used Spring Data JDBC. Mapping Postgres native enums and JSONB through
custom converters required PGobject wrappers, value-class hacks for the JSON column, and
fragile read/write paths for enum types. Switching to Spring Data JPA / Hibernate
collapsed all of that into `@Enumerated(EnumType.STRING)` and
`@JdbcTypeCode(SqlTypes.JSON)`. The migration was simplified to `VARCHAR(32) + CHECK`
constraints (so any client can read the columns without enum-type plumbing), and the
JSONB policy column maps to a Kotlin data class with zero hand-written serialisation.

### Strict separation between API DTOs and domain types

Controllers accept and return only API-layer DTOs (`AllocationPolicyDto`,
`SlotRequirementDto`, `EquipmentResponse`, …). The previous shape returned the JPA entity
`Equipment` directly, which leaked `@Version`, `createdAt`, `updatedAt`, and the policy
storage shape. Today the wire format can evolve without touching storage, and storage can
evolve without breaking clients. Domain enums (`EquipmentType`, `EquipmentStatus`,
`AllocationState`) are deliberately *not* duplicated — they're values without behaviour,
and mirroring them adds cost without strengthening the boundary.

### Use-case classes per operation

The application layer is exclusively use-case classes (`CreateAllocationUseCase`,
`ConfirmAllocationUseCase`, `RetireEquipmentUseCase`, …) instead of broad CRUD services.
Each class has one public method and one transactional concern. This makes the read path
of any controller method trivially clear, makes testing per-use-case straightforward, and
keeps "does this transition need a lock?" a property of the use case, not buried in a
catch-all `service`. The exception is `AllocationProcessor`, which is an event listener
rather than a use case, since the trigger is internal, not an API call.

### Type-partitioned Hungarian + argmax fast path

The Hungarian solver was originally invoked once on a single `N × N` matrix where `N` was
the entire inventory. Two observations changed this:
1. The bipartite graph is **disconnected by equipment type** — a `MONITOR` slot has cost
   `+∞` to a `KEYBOARD`. Independent components can be solved independently. So the
   allocator now runs one Hungarian per type on `nₜ × nₜ`, sized to that type's pool.
2. When a type contributes a single slot, the assignment problem reduces to *"pick the
   highest-scoring eligible candidate"* — a one-pass argmax, no matrix at all.
   See benchmark results below: 5 k inventory `multiSlot` dropped from ~73 s/op to
   ~65 µs/op (≈10⁶× speedup); the residual cost is the cubic Hungarian on the
   `MONITOR×2` sub-pool, which only runs when a slot has `count > 1`.

### Per-slot scoring preferences

`preferredBrands` and `preferRecent` both live on `SlotRequirement`, not on
`AllocationPolicy`. A request can say "newest monitors, any keyboard" — different
slots in the same policy can have different preferences. The earlier shape with
`preferRecent` global to the policy was inconsistent with how `preferredBrands` was
already modelled and forced a single recency rule for the whole bundle.

### `VARCHAR + CHECK` instead of Postgres native enums

Status and type columns are stored as `VARCHAR(32)` with `CHECK (col IN (…))`. Native
Postgres enums are slightly more compact but require migrations to add a value
(`ALTER TYPE`), need cast plumbing in clients, and don't map cleanly across drivers.
With check-constrained varchars, adding a value is a one-line ALTER on the constraint and
any SQL client reads them as strings. The performance difference is negligible for tables
of this size.

### `@ElementCollection` for `allocation_equipment`

The link rows between an allocation and the equipment it reserves are modelled as a JPA
`@ElementCollection` of `AllocationEquipmentSlot` embeddable, owned by `AllocationRequest`.
Cascade and lifecycle are tied to the parent — cancelling an allocation clears the
collection and JPA deletes the join rows automatically. The unique index on
`allocation_equipment(equipment_id)` is the database-level guarantee that one piece of
equipment is in at most one active allocation; the application logic and the index agree.

### OpenAPI generated

`springdoc-openapi` reads the controllers and DTOs at runtime; a JUnit test
(`OpenApiSpecExportTest`) boots the context, fetches `/v3/api-docs.yaml`, and writes it
to `openapi.yaml` at the repo root. The committed file then makes API changes visible in
PR diffs. We pin a stable `Server` URL so the spec doesn't churn on the random port the
test uses. There is no separate hand-maintained schema to drift from the code.

### AssertJ over JUnit assertions

Tests use AssertJ's fluent API throughout (`assertThat(x).isEqualTo(y)` etc). The chained,
type-aware assertions (`containsEntry`, `containsExactlyInAnyOrder`, `isInstanceOf`) read
better and produce richer failure messages than `assertEquals` / `assertTrue`. Mixing the
two styles within a project hurts readability for no benefit.

### JMH micro-benchmarks for the allocator

Algorithm performance was a real question (the first version had `O(N³)` over the full
inventory). JMH gives us reproducible, warmed-up measurements with proper percentile
distributions, which is what was needed to validate the type-partitioning + argmax
optimisations and quantify the residual `count > 1` cliff. Output mode is `SampleTime`
so we can see p50 vs p99 — averages alone would have hidden the long tail.

## Running it

```sh
docker compose up -d           # starts Postgres on :5432
./gradlew bootRun              # runs the API on :8080, applies Flyway migrations on startup
./gradlew test                 # full test suite (Testcontainers — Docker required)
./gradlew jmh                  # benchmarks; results in build/results/jmh/results.txt
```

## Allocation algorithm

The allocator solves a small, well-shaped instance of the **assignment problem**: given a
set of slots (each with a type and constraints) and a pool of candidate equipment, pick at
most one equipment per slot so every slot is satisfied and the total *score* is maximised.

### Scoring

Every (slot, equipment) pair gets an integer score:

```
score  = round(conditionScore × 100)               // 0..100
       + 50  if equipment.brand ∈ slot.preferredBrands
       + min(30, max(0, 365 − ageInDays))   if slot.preferRecent
```

Hungarian works on costs, so internally the score is negated (`cost = −score`); ineligible
pairs (wrong type or `conditionScore < minCondition`) get `+∞`.

### Why not greedy

The textbook greedy ("for each slot, pick the highest-scoring eligible candidate") fails
on cross-slot constraints. Example with two monitors and two slots:

| | Monitor A (cond 0.9) | Monitor B (cond 0.7) |
| --- | --- | --- |
| Slot 1 (`minCondition = 0.8`) | eligible | **ineligible** |
| Slot 2 (`minCondition = 0.5`) | eligible | eligible |

Greedy starts on slot 2, takes the highest score (Monitor A), and slot 1 is then
infeasible. The optimal assignment is A → slot 1, B → slot 2. To get it right in general we
need a global optimiser. Hungarian (Kuhn–Munkres) gives an exact `O(n³)` minimum-cost
matching.

### Type-partitioning

A `MAIN_COMPUTER` slot has cost `+∞` to any keyboard, so the bipartite graph is
**disconnected by equipment type**. The allocator exploits this:

1. Expand `count` slots into per-unit slots, remembering each one's original index.
2. Group slots by `type`; group available equipment by `type`.
3. For each type independently, solve the smaller per-type sub-problem.

So instead of one `N × N` Hungarian (where `N` is total inventory), we run one Hungarian
per type, sized at most `nₜ × nₜ` (where `nₜ` is the inventory of that type). With the four
types we have, this brings the worst case down by roughly `1/T²` (here `T = 4` →
~16× speedup at uniform distribution, more in practice because of cache locality and the
Hungarian's hidden constants).

### Single-slot fast path

Most real policies have at most one slot per type (one main computer, one keyboard, one
mouse, plus a small number of monitors). When a type contributes exactly one slot, the
matching reduces to *"pick the eligible candidate with the highest score"* — a single
linear scan, no matrix. Hungarian only runs for types where multiple slots compete for the
same pool (typically: monitors with `count > 1`).

### Failure semantics

A type sub-problem returns `null` when no feasible matching exists — either the pool is
smaller than the slot count, or every Hungarian column-update reaches a state with no
unvisited finite-cost column. The allocator translates this into a single
`AllocationResult.Failure` with the offending type and counts.

## Benchmark results

JMH 1.36, `Mode.SampleTime`, 1 fork × 2 warmup iterations × 3 measurement iterations × 2 s
each, JDK 25, M-series Mac, 4 equipment types, deterministic seed. Times are per allocation
call.

### `singleSlot` — one slot, one type

```
SlotRequirement(MAIN_COMPUTER, minCondition = 0.7)
```

Hits the single-slot fast path — one argmax scan over the matching-type pool.

| Inventory | p50 | p99 | Mean |
| ---: | ---: | ---: | ---: |
|  1 000 |  15 µs |  83 µs |  21 µs |
|  5 000 |  76 µs | 150 µs |  80 µs |
| 10 000 | 130 µs | 508 µs | 147 µs |

Linear in the per-type pool size (~1/4 of inventory). p99 vs p50 spread is mostly GC and
JIT noise on a non-quiesced laptop.

### `multiSlot` — four slots, four types

```
MAIN_COMPUTER + MONITOR + KEYBOARD + MOUSE   (preferRecent on the first two)
```

Every type contributes one slot, so the whole policy resolves to four argmax scans.

| Inventory | p50 | p99 | Mean |
| ---: | ---: | ---: | ---: |
|  1 000 |  19 µs |  64 µs |  21 µs |
|  5 000 |  61 µs | 143 µs |  66 µs |
| 10 000 | 161 µs | 379 µs | 171 µs |

Roughly the same as `singleSlot` because the scans run sequentially over disjoint type
pools whose sizes sum to the full inventory.

### `countExpanded` — `count = 2` monitor slot

```
MAIN_COMPUTER + MONITOR×2 + KEYBOARD(preferred Apple)
```

`MAIN_COMPUTER` and `KEYBOARD` still hit the fast path. The `MONITOR×2` slot is expanded
into two unit-slots, both of type `MONITOR` competing over the same pool — so this type
now goes through Hungarian on a `monitorPoolSize × monitorPoolSize` matrix.

| Inventory | Monitor pool | p50 | p99 | Mean |
| ---: | ---: | ---: | ---: | ---: |
|  1 000 |   ~250 |   15 ms |   34 ms |   17 ms |
|  5 000 | ~1 250 |  1.7 s  |  2.6 s  |  1.9 s  |
| 10 000 | ~2 500 | 13.6 s  | 14.1 s  | 13.7 s  |

This curve is the `O(n³)` cost of Hungarian on the monitor pool: tripling `n` multiplies
runtime by ~27, which is what we observe (15 ms → 1700 ms is 113×, 1700 ms → 13700 ms is
8×; both bracket the cubic prediction within a single fork's noise). The Hungarian itself
is unchanged from the textbook potential-based variant; the savings elsewhere came from
not running it across types and not running it at all when one slot per type suffices.

### Where the cliff is

Sub-millisecond response is realistic up to ~10 000 inventory items as long as no slot
has `count > 1`. Once a slot has multiple units, performance is bounded by Hungarian on
the matching-type sub-pool. If 5 k+ inventory with multi-count slots is in scope, the
next optimisation is to **prune the candidate pool to the top-K by upper-bound score**
before invoking Hungarian — since the score function is the sum of three independent
non-negative terms, an admissible upper bound is cheap to compute, and `K = 4 × slotCount`
is empirically sufficient.

