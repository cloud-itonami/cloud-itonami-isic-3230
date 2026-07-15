# ADR-0001: SportsGoodsAdvisor ⊣ Sports Goods Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-3230` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-3230` publishes an OSS blueprint for sports-goods-
plant **plant operations coordination** (production-batch product-
type/impact-rating/weight/defect-rate data logging, molding/assembly/
finishing-equipment maintenance scheduling, safety-concern flagging,
and outbound product shipment coordination). Like every actor in this
fleet, the blueprint alone is not an implementation: this ADR records
the governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

Identity ({:id "3230" :name "Manufacture of sports goods"}) was
independently verified against a fresh clone of `kotoba-lang/
industry`'s `resources/kotoba/industry/registry.edn` before any work
began, per this fleet's ID/name-mismatch caution (prior agents in this
fleet have mislabeled their assigned ISIC class). The entry's own
`:repo` field pointed at a stale, never-created `gftdcojp/cloud-
itonami-C3230` placeholder; the real `cloud-itonami` org target name
was independently confirmed 404 via `gh api repos/cloud-itonami/
cloud-itonami-isic-3230` before scaffolding began.

The closest domain analog is `cloud-itonami-isic-3211` (Manufacture of
jewellery and related articles): both are back-office coordination
actors for a fixed processing PLANT with precision production
equipment and a real safety/consumer-protection dimension, and both
share the same four-op shape (`:log-production-batch`/
`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment`)
and the same two-entity verified/registered gate structure (equipment
for maintenance scheduling, batch for shipment coordination). This
build mirrors `cloud-itonami-isic-3211`'s architecture closely but
adapts the hazard profile and equipment/product vocabulary to the
sports-goods plant: this vertical's central physical hazard is
molding/assembly/finishing-equipment operation, and its central
consumer-protection dimension is impact-protection/product-safety
standard compliance for protective gear and fitness equipment (rather
than 3211's materials-safety solvent/acid and theft/security/
authenticity-fraud profile); its permanent equipment-actuation block
guards molding/assembly-line EQUIPMENT (`:actuate-equipment?`) rather
than casting/setting/polishing EQUIPMENT; its production-batch record
declares a `:product-type` (closed set spanning balls/rackets/
protective-gear/fitness-equipment) and an `:impact-rating-percent`
(a compliance-percentage-against-standard-threshold analog of 3211's
per-mille purity fineness, plausibility-checked 1-100) and a
`:weight-grams` reading (plausibility-checked above 0 up to a
1,000,000g/1-tonne per-batch ceiling, reflecting this vertical's
heavier fitness-equipment product category vs. 3211's 5000g precious-
metal ceiling) in addition to a `:defect-rate-percent`, rather than
3211's `:metal-type`/`:purity-permille`/`:weight-grams`; and its
shipment quantity is tracked in finished-goods-piece UNITS
(`:units`/`:quantity-units`/`:shipped-units`), the same counted-not-
weighed shape 3211 uses for finished jewellery pieces.

This vertical additionally has a DOMAIN-SPECIFIC permanent block
mirroring 3211's hallmarking/purity-assay block but for a different
regulatory/attestation regime: manufacture of protective sports gear
and fitness equipment is subject to product-safety/impact-protection
compliance certification (the compliance mark an accredited testing/
certification body applies to certify a product meets an applicable
impact-protection/product-safety standard, e.g. ASTM/CPSC/EN
protective-equipment standards or equivalent national/regional
certification regimes). This actor is never the product-safety-
certification authority -- any proposal (regardless of op) that
declares `:issue-safety-certification? true` is a HARD, PERMANENT,
unconditional block
(`sportsgoodsmfg.governor/safety-certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical has NO pre-existing `kotoba-lang/sportsgoodsmfg`-style
capability library to wrap (verified: no such repo exists, and no
`sport`/`sports`-named manufacturing-capability repo exists in
`kotoba-lang` either, via GitHub code/repo search). This build
therefore uses self-contained domain logic -- pure functions in
`sportsgoodsmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, impact-rating-percent
plausibility validation, weight-grams plausibility validation,
defect-rate plausibility validation) are re-verified independently by
the governor, the same "ground truth, not self-report" discipline
established across prior actors (most directly `cloud-itonami-isic-
3211`'s `jewellerymfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:sports-goods-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "sports-goods-plant-operations-governor"
--owner cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external sports-goods-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
sports-goods-plant vertical has NO pre-existing capability library to
wrap. The equipment/batch-verification / shipment-quantity /
product-type / impact-rating-percent / weight-grams / defect-rate
validation functions live as pure functions in
`sportsgoodsmfg.registry` and are re-verified independently by
`sportsgoodsmfg.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-3211`'s `jewellerymfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of sports-goods-
plant plant operations. It does NOT:
- Control molding, assembly, or finishing equipment directly
- Make plant-safety or product-safety-certification decisions (exclusive to the human plant supervisor / accredited testing-and-certification body)
- Actuate molding/assembly-line equipment
- Self-issue a product-safety/impact-protection compliance certification

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY/CONSUMER-PROTECTION BOUNDARY**: sports-goods
manufacturing is a safety- and consumer-protection-relevant domain
(molding/assembly/finishing-line equipment hazard, impact-protection/
product-safety standard compliance for protective gear and fitness
equipment, consumer-protection consequence downstream).
Safety-concern flagging NEVER auto-commits. All safety concerns
escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (materials-safety hazard, product-safety-
standard/impact-protection-rating concern) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" — it is a
circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-3211`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether
a batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity — never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into thirteen concrete
checks in `sportsgoodsmfg.governor`, mirroring `cloud-itonami-isic-
3211`'s own elaboration of its HARD invariants into concrete checks,
adapted to this vertical's impact-rating/weight plausibility fields)
block proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct molding/assembly-line-equipment control, equipment actuation, or self-issued product-safety/impact-protection compliance certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Sports-goods-plant plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no product-safety/impact-protection
compliance certification can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into thirteen concrete governor checks) protect against scope creep
into unauthorized equipment operation, equipment actuation, or
product-safety-certification self-issuance. Safety concerns are a
circuit-breaker, not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and product-
safety/impact-protection certification issuance remain human-/
institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-3230`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, safety-certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-impact-rating, invalid-weight,
  invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
