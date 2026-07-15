# cloud-itonami-isic-3230: Manufacture of sports goods

Open Business Blueprint for **ISIC 3230**: manufacture of sports goods — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **sports-goods plant operations**: production-batch data logging (product-type/impact-rating/weight/defect-rate), molding/assembly/finishing-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for sports-goods-plant
plant operations: run by a qualified operator so a plant keeps its
own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not molding/assembly-line control

ISIC 3230 covers the **sports-goods plant** that molds, assembles, and
finishes balls, rackets, protective gear, and fitness equipment. This
actor coordinates the back-office record keeping around that plant —
it never touches the molding/assembly-line equipment directly, and it
is never the product-safety-certification authority (e.g. an
accredited testing/certification body) that certifies a product's
compliance with an applicable impact-protection/product-safety
standard.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — molding/assembly batch, output-quality data logging (product-type/impact-rating/weight/defect-rate; administrative, not an operational decision)
- `:schedule-maintenance` — molding/assembly-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/product-safety-standard (e.g. impact-protection rating) concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety- and consumer-protection-
relevant domain** (molding/assembly/finishing-line equipment,
impact-protection/product-safety standard compliance, consumer-
protection consequence downstream for protective gear and fitness
equipment):

- Does NOT control molding, assembly, or finishing equipment directly
- Does NOT make plant-safety or product-safety-certification decisions (that's the plant supervisor's / accredited testing-and-certification body's exclusive human/institutional authority)
- Does NOT actuate molding/assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue a product-safety/impact-protection compliance certification (the accredited testing/certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and product-safety certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`sportsgoodsmfg.operation/build`, a langgraph-clj StateGraph):
1. **`sportsgoodsmfg.advisor`** (sealed intelligence node, `SportsGoodsAdvisor`): proposes decisions only, never commits
2. **`sportsgoodsmfg.governor`** (independent, `Sports Goods Plant Operations Governor`): validates against domain rules, re-derived from `sportsgoodsmfg.registry`'s pure functions and `sportsgoodsmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct molding/assembly-line-equipment control)
     - Directly actuating molding/assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a product-safety/impact-protection compliance certification (`:issue-safety-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically/certifiably implausible `:impact-rating-percent` value on a production-batch patch
     - No physically implausible `:weight-grams` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`sportsgoodsmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`sportsgoodsmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
