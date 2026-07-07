# ADR-0001: cloud-itonami-isic-7500 -- VetOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`
  ADR-0001s (the pattern this ADR ports); ADR-2607071250/ADR-2607071320/
  ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654/
  ADR-2607071717/ADR-2607071732 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`, the eight verticals built outside
  ADR-2607032000's original insurance/real-estate batch -- this is the
  ninth)
- Context: Continuing the standing "pick a new ISIC blueprint vertical"
  direction past `9200`, this ADR deepens `cloud-itonami-isic-7500`
  (veterinary activities) from `:blueprint` to `:implemented`, the
  seventeenth actor in this fleet -- the FIRST veterinary/animal-health
  vertical (ISIC division 75), a domain deliberately close in SHAPE to
  `8620`'s medical/dental practice (single actuation, contraindication
  screening) but genuinely distinct in regulatory grounding (animal
  rather than human patients) and, uniquely, introducing a food-safety/
  temporal concept `8620` had no analog for.

## Problem

A veterinary practice's treatment-administration workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction veterinary-licensing correctness** -- an official
   spec-basis citation from a real veterinary-licensing authority
   (MAFF/AAVSB/RCVS/Bundestierärztekammer), never fabricated.
2. **Contraindication safety** -- reuses `clinic.registry/treatment-
   contraindicated?`'s set-membership/conflict shape verbatim for the
   veterinary domain (the same real clinical-safety concept applies to
   animal patients as to human patients).
3. **Food-safety withdrawal sufficiency** -- for a food-producing
   animal, does its own planned-harvest timeline leave enough time for
   a drug's required withdrawal period to fully elapse? A genuinely
   NEW real-world regulatory concept for this fleet, combining a
   MINIMUM-threshold pure-ground-truth recompute (`marketadmin.
   governor`'s/`registrar.governor`'s shape) with a type-tag gate
   (`accounting.governor`'s shape) in a single check -- the FIRST
   check in this fleet to model a food-safety/temporal-sufficiency
   concept.
4. **Clinician credential currency** -- reuses the unconditional-
   evaluation screening discipline for a SEVENTH distinct grounding.
5. **Real actuation, once** -- administering a real treatment,
   prescription or procedure is an irreversible act an animal
   patient's health outcome, and for food-producing animals the food
   supply itself, depend on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a veterinary practice with an LLM" but
"seal the LLM inside a trust boundary and layer evidence-sufficiency,
contraindication-safety verification, food-safety withdrawal
verification, credential-currency screening, audit and human-approval
on top of it, while structurally fixing the one real actuation event
as human-only."

## Decision

### 1. VetOps-LLM is sealed into the bottom node; it never treats directly

`veterinary.vetopsllm` returns exactly four kinds of proposal: intake
normalization, jurisdiction licensing checklist, credential screening,
and treatment-administration draft. No proposal writes the SSoT or
commits a real treatment administration directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 veterinary operation

`veterinary.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `treatment-contraindicated?` reuses `clinic.registry`'s shape verbatim

The real clinical-safety concept (a proposed treatment must not appear
in a patient's own known contraindication/allergy list) is identical
for animal patients and human patients -- this is a direct,
unmodified reuse of `clinic.governor/contraindicated-violations`'s
set-membership/conflict shape, not a reinvention.

### 4. `withdrawal-period-insufficient?` is a genuinely NEW check for this fleet: a food-safety/temporal-sufficiency concept

For a food-producing animal ONLY, a drug's required withdrawal period
must fully elapse before the animal's planned harvest/slaughter date.
`withdrawal-period-insufficient-violations` combines two previously-
separate patterns in this fleet: a MINIMUM-threshold pure-ground-truth
recompute (`marketadmin.governor/listing-standard-not-met-violations`/
`registrar.governor/credits-not-sufficient-violations`'s shape) GATED
on a boolean type tag (`:food-producing?`, the same discriminating-
flag discipline `accounting.governor/wrong-engagement-type-violations`
establishes) -- the first check in this fleet to combine both patterns
in one check, and the first to touch a food-safety/temporal concept at
all.

### 5. The withdrawal-period check guards on `:food-producing?` FIRST, deliberately informed by `6920`'s bug

`withdrawal-period-insufficient?`'s implementation checks
`(and food-producing? (< days-until-planned-harvest withdrawal-period-
days))` -- the `and` short-circuits before touching the harvest/
withdrawal fields when `:food-producing?` is false or nil, so a
companion animal (whose harvest/withdrawal fields are legitimately
`nil`) never triggers a `NullPointerException`. This guard-the-type-
tag-first discipline is DIRECTLY informed by `cloud-itonami-isic-
6920`'s ADR-0001 (a pure ground-truth recompute whose inputs are only
meaningful for a subset of an entity's possible states must guard on
the discriminating flag before touching the type-specific fields) --
applied here BEFORE writing any code, not discovered as a bug
afterward.

### 6. Credential screening reuses the unconditional-evaluation discipline for a seventh distinct grounding

`credential-not-current-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for a further application in this fleet, for BOTH
`:credential/screen` and `:treatment/administer` -- the SAME shape
`clinic.governor/credential-not-current-violations` establishes for
its own domain, reused verbatim (both are the "is the treating
clinician's own license current" concept).

### 7. Single actuation event

`veterinary.governor`'s `high-stakes` set has exactly one member
(`:actuation/administer-treatment`), matching `6511`'s/`6621`'s/
`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s single-actuation shape --
this domain has one distinct real-world clinical act (administering a
treatment), not several independently-gated acts.

### 8. Double-treatment guard checks a dedicated boolean fact, not `:status`

`already-treated-violations` checks `:treated?`, a dedicated boolean
set once and never cleared, rather than a `:status` value that could
legitimately advance past a checked state (the exact trap `cloud-
itonami-isic-6492`'s ADR-0001 documents in detail, explicitly avoided
BY DESIGN in `6920`'s, `6611`'s, `7120`'s, `8620`'s, `8530`'s and
`9200`'s equivalent guards). This actor's `:status` never needs to
encode "has this actuation already happened" at all, so there is no
analogous status-lifecycle risk to fall into here -- a deliberate
architectural choice applied here for a seventh consecutive time.

### 9. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`, and unlike most other actors
in this fleet (each referencing its own `kotoba-lang/*` capability
lib), this vertical's case records are practice-specific rather than a
shared cross-operator data contract -- `veterinary.*` runs on the
generic identity/forms/dmn/bpmn/audit-ledger stack only, per the
blueprint's own explicit statement.

### 10. No bug this time

Like `7120`/`8620`/`8530`/`9200` (and unlike `6492`'s status-lifecycle
bug or `6920`'s NullPointerException), this build's test suite, lint,
and demo-ledger verification all passed clean on the first run -- the
withdrawal-period guard design (Decision 5) was DELIBERATELY informed
by `6920`'s bug before writing any code, the strongest possible
demonstration that this fleet's own documented lessons can prevent a
recurrence rather than merely explain one after the fact. The demo
(`clojure -M:dev:run`) was still independently verified against the
printed audit ledger -- basis tags `:no-spec-basis` ·
`:contraindicated` · `:withdrawal-period-insufficient` · `:credential-
not-current` · `:already-treated` all appear exactly where the sim
script intends, and the treatment history contains exactly one
drafted record after the double-administration attempt is held -- the
same discipline that caught every real bug in this fleet so far,
applied here and finding nothing to fix.

## Consequences

- (+) Veterinary/animal-health gets the same governed, auditable-actor
  treatment as the sixteen prior actors, extending the pattern to a
  genuinely different domain (ISIC division 75) for the first time,
  while proving the architecture generalizes across near-identical-
  shape domains (human vs. animal medicine) without becoming a
  copy-paste exercise -- the withdrawal-period check is a real,
  distinguishing addition.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/veterinary/phase_test.clj`'s `treatment-
  administer-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  veterinary/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) `withdrawal-period-insufficient?`/`withdrawal-period-
  insufficient-violations` is a genuine new check for this fleet (a
  food-safety/temporal-sufficiency concept combining a minimum-
  threshold recompute with a type-tag gate), regression-tested by
  `test/veterinary/governor_contract_test.clj`'s `withdrawal-period-
  insufficient-is-held`.
- (+) The `6920`-informed guard-the-type-tag-first discipline (Decision
  5) demonstrates a lesson applied PROACTIVELY (before any code was
  written) rather than reactively (after a bug was found) -- the
  clearest evidence yet that this fleet's documented lessons transfer.
- (+) Both the demo and the full test suite passed clean on the first
  run -- no bug this time, unlike `6492`/`6920`.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `veterinary.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `withdrawal-period-insufficient?` models only a single flat
  withdrawal-period figure per case, not a full withdrawal-interval/
  residue-testing regime (species/drug/route-of-administration-
  specific withdrawal tables, residue-testing verification are out of
  scope -- see that fn's own docstring); real practice-management-
  system integration and ongoing herd-health/food-safety-residue
  monitoring are all out of scope for this OSS actor -- each
  operator's responsibility (see README's coverage table).
- 33 tests / 139 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eight of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`; mixing a different ISIC division (75, distinct from those eight's 64/66/69/71/86/85/92) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-7500` at `:blueprint` only | ❌ | The standing direction continues past `9200`; veterinary practice is a natural, well-precedented next domain that also introduces a genuinely new regulatory concept (food-safety withdrawal periods) this fleet had not yet modeled |
| Skip the withdrawal-period check since the blueprint's Core Contract only names "administering a treatment, prescription or procedure" | ❌ | Food-safety withdrawal periods are a REAL, foundational veterinary-regulatory concept directly relevant to treatment administration for food-producing animals -- omitting it would understate what a real veterinary governor must check, and the blueprint's scope (all veterinary treatment administration) naturally includes livestock |
| Model a full withdrawal-interval/residue-testing regime for conformance-test rigor | ❌ | Genuinely more complex real-world veterinary-pharmacology/food-safety logic that this R0 does not claim to model correctly -- honestly scoped to a single flat withdrawal-period figure instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/veterinary`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning `6920`'s/`7120`'s/`8620`'s/`8530`'s/`9200`'s ADRs already established |
