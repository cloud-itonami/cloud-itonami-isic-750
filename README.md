# cloud-itonami-isic-7500

Open Business Blueprint for **ISIC Rev.5 7500**: Veterinary activities.
This repository publishes a veterinary actor -- case intake,
jurisdiction licensing assessment, clinician-credential screening and
treatment administration -- as an OSS business that any qualified,
licensed veterinary practice can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200)) --
the first veterinary/animal-health vertical (ISIC division 75) in
this fleet. Here it is **VetOps-LLM ⊣ Veterinary Care Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a case
> summary, normalizing intake, and checking whether a proposed
> treatment appears on a patient's own recorded contraindication list
> -- but it has **no notion of which jurisdiction's veterinary-
> licensing requirements are official, no license to administer a
> real treatment, prescription or procedure, and no way to know on its
> own whether a food-producing animal's own planned-harvest timeline
> actually leaves enough time for a drug's required withdrawal period
> to elapse**. Letting it administer a treatment directly invites
> fabricated jurisdiction citations, a treatment administered against
> a patient's own known contraindication, and a food-safety withdrawal
> shortfall being quietly waved through -- and liability for whoever
> runs it. This project seals the VetOps-LLM into a single node and
> wraps it with an independent **Veterinary Care Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers case intake through jurisdiction licensing
assessment, clinician-credential screening and treatment
administration. It does **not**, by itself, hold a license to operate
a veterinary practice in any jurisdiction, and it does not claim to.
It also does **not** model a full drug-interaction/allergy cross-
reference database, nor a full withdrawal-interval/residue-testing
regime -- no interaction severity, no dosage-dependent
contraindications, no species/drug/route-of-administration-specific
withdrawal tables, no residue-testing verification (see `veterinary.
registry/treatment-contraindicated?`'s and `withdrawal-period-
insufficient?`'s own docstrings for the honest simplifications these
make). Whoever deploys and operates a live instance (a licensed
veterinary practice) supplies the jurisdiction-specific license, the
real clinical/food-safety expertise and the real practice-management-
system integrations, and bears that jurisdiction's liability -- the
software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch for every new market.

### Actuation

**Administering a real treatment, prescription or procedure is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`veterinary.governor`'s `:actuation/administer-
treatment` high-stakes gate and `veterinary.phase`'s phase table,
which never puts `:treatment/administer` in any phase's `:auto` set)
-- see `veterinary.phase`'s docstring and `test/veterinary/
phase_test.clj`'s `treatment-administer-never-auto-at-any-phase`. The
actor may draft, check and recommend; a human licensed veterinarian is
always the one who actually administers a treatment. Like `6511`/
`6621`/`6629`/`6612`/`6492`/`7120`/`8620`, this actor has ONE actuation
event.

## The core contract

```
case intake + jurisdiction facts (veterinary.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ VetOps-LLM   │ ─────────────▶ │ Veterinary Care             │  (independent system)
   │  (sealed)    │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ contraindicated ·
                                 │             │           │ withdrawal-period-
                           record + ledger  escalate ─▶ human   insufficient (NEW:
                                             (ALWAYS for         food-safety/temporal) ·
                                              :treatment/           credential-not-current ·
                                              administer)          already-treated
```

**The VetOps-LLM never administers a treatment the Veterinary Care
Governor would reject, and never does so without a human sign-off.**
Hard violations (fabricated jurisdiction requirements; unsupported
licensing evidence; a proposed treatment that appears on the patient's
own contraindication list; a food-producing animal's planned-harvest
timeline leaving insufficient time for the drug's withdrawal period; a
lapsed clinician license; a double administration) force **hold** and
*cannot* be approved past; a clean treatment proposal still always
routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean lifecycle (treatment administration) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a diagnostic-imaging/
handling robot assists physical animal restraint and imaging, under
the actor, gated by the independent **Veterinary Care Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Veterinary Care Governor, treatment-administration draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`7500`). Like `6920`/`7120`/`8620`/`8530`/`9200`, this vertical's case
records are practice-specific rather than a shared cross-operator data
contract, so `veterinary.*` runs on the generic identity/forms/dmn/
bpmn/audit-ledger stack only -- no bespoke domain capability lib to
reference at all.

## Layout

| File | Role |
|---|---|
| `src/veterinary/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + treatment-administration history. No dynamically-filed sub-record -- the actuation op acts directly on a pre-seeded case, and the double-administration guard checks a dedicated `:treated?` boolean rather than a `:status` value |
| `src/veterinary/registry.cljc` | Treatment-administration draft records, plus `treatment-contraindicated?` (reuses `clinic.registry`'s set-membership/conflict shape) and `withdrawal-period-insufficient?` -- the FIRST check in this fleet to model a food-safety/temporal-sufficiency concept, combining a minimum-threshold recompute with a type-tag gate |
| `src/veterinary/facts.cljc` | Per-jurisdiction veterinary-licensing catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/veterinary/vetopsllm.cljc` | **VetOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/credential-screening/treatment-administration proposals |
| `src/veterinary/governor.cljc` | **Veterinary Care Governor** -- 5 HARD checks (spec-basis · evidence-incomplete · contraindicated · withdrawal-period-insufficient, pure ground-truth type-gated minimum-threshold recompute · credential-not-current, unconditional evaluation) + already-treated guard + 1 soft (confidence/actuation gate) |
| `src/veterinary/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (treatment always human; case intake is the ONLY auto-eligible op, no direct clinical risk) |
| `src/veterinary/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/veterinary/sim.cljc` | demo driver |
| `test/veterinary/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers case intake through jurisdiction licensing
assessment, clinician-credential screening and treatment
administration -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Case intake + per-jurisdiction veterinary-licensing checklisting, HARD-gated on an official spec-basis citation (`:case/intake`/`:jurisdiction/assess`) | A full drug-interaction/allergy cross-reference database, and a full withdrawal-interval/residue-testing regime (species/drug/route-of-administration-specific withdrawal tables, residue-testing verification -- see `treatment-contraindicated?`'s and `withdrawal-period-insufficient?`'s docstrings) |
| Clinician-credential screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:credential/screen`) | Real practice-management-system integration, tax/regulatory reporting |
| Treatment administration, HARD-gated on the proposed treatment not appearing on the patient's own contraindication list, a food-producing animal's withdrawal-period sufficiency, and a double-administration guard (`:treatment/administer`) | Ongoing herd-health/food-safety-residue monitoring itself |
| Immutable audit ledger for every intake/assessment/screening/administration decision | |

Extending coverage is additive: add the next gate (e.g. a species-
appropriate-dosage check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor re-
verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`veterinary.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `veterinary.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `veterinary.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `VetOps-LLM` + `Veterinary Care Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the sixteen
prior actors' architecture. See `docs/adr/0001-architecture.md` for
the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
