# ADR-0001: cloud-itonami-isic-750 -- VetOpsAdvisor as a contained intelligence node, coordination-only scope

- Status: Accepted (2026-07-19)
- Related: `cloud-itonami-isic-861` (`hospitalops.*`, the coordination-only
  vs. full-clinical-workflow 3-digit/4-digit split this ADR ports),
  `cloud-itonami-isic-8610` (`hospital.*`, `861`'s full-clinical-workflow
  sibling), `cloud-itonami-isic-7500` (`veterinary.*`, this repo's own
  full-clinical-workflow sibling)

## Context

This repo (`cloud-itonami-isic-750`, ISIC group "Veterinary activities")
was originally published as a byte-for-byte copy of
`cloud-itonami-isic-7500` (ISIC class "Veterinary activities") --
`blueprint.edn`, `README.md`, and the entire `src/veterinary/*` +
`test/veterinary/*` tree were identical between the two repos, and
`blueprint.edn` even self-identified as `cloud-itonami-isic-7500`. ISIC
division 75 is one of the small number of ISIC divisions that bottoms out
in a single group and a single class (group 750 has exactly one class,
7500), so the two repos legitimately share the same real-world activity
at the numeric-code level -- but sharing a code is not license to ship
identical content. Every other 3-digit-group/4-digit-class pair in this
fleet uses a coordination-only-vs-full-clinical-workflow split (e.g.
`861`'s `hospitalops.*` vs. `8610`'s `hospital.*`), and this repo needed
to be brought into that same pattern rather than remain a relabeled
duplicate.

## Problem

A pure copy of the class-level clinical actor is not a legitimate
group-level actor:

1. It claims authority (case intake, jurisdiction licensing assessment,
   clinician-credential screening, treatment administration) that a
   3-digit group repo in this fleet has never been given -- every
   sibling group repo is deliberately narrower than its class sibling(s).
2. It gives operators no reason to deploy both repos side by side --
   they would be running the exact same actor twice under two different
   git remotes.
3. Its own `blueprint.edn` metadata (`:itonami.blueprint/id
   "cloud-itonami-isic-7500"`) was simply wrong for this repo.

## Decision

### 1. Re-scope to administrative/facility coordination only, matching `861`'s pattern

`vetops.*` (replacing `veterinary.*`) drafts exactly five kinds of
back-office proposal from a closed allowlist: exam-room/appointment-slot
scheduling, kennel/boarding-run assignment, non-clinical supply
coordination, staff shift proposals, and facility safety-concern
flagging. It never reaches diagnosis, treatment, medication/vaccine/
anesthesia administration, surgical/dental procedures, euthanasia
decisions, or license/compliance enforcement -- those remain
`cloud-itonami-isic-7500`'s exclusive territory.

### 2. VetOpsAdvisor is sealed into the bottom node; it never proposes clinical content

`vetops.advisor` returns exactly five kinds of proposal, all
`:effect :propose`. No proposal writes the SSoT directly, and none of
the five kinds can legitimately touch clinical territory -- `vetops.
governor`'s `scope-exclusion-violations` independently re-scans every
proposal's op/summary/rationale/cites/value for exactly this failure
mode regardless of what the advisor intended.

### 3. Three HARD checks, MAXIMALLY conservative scope exclusion, matching `861`'s discipline

Resource-unverified, effect-not-propose, and scope-exclusion (the same
three-check shape `hospitalops.governor` established for ISIC-861,
applied here with a veterinary-specific banned-term list: diagnosis,
treatment, medication/vaccine/anesthesia, surgical/dental procedures,
euthanasia, controlled substances, license/compliance enforcement).

### 4. One ESCALATE gate, never auto-eligible at any phase

`:flag-safety-concern` always escalates to a human, enforced
independently by both `vetops.governor/always-escalate-ops` and
`vetops.phase`'s phase table (which never puts `:flag-safety-concern` in
any phase's `:auto` set) -- two layers, not one.

### 5. Avoid the self-triggering scope-filter trap `hospitalops.render-html` already documented

A naive "describe what this op does NOT do" rationale/summary string
that repeats the governor's own banned substrings (e.g. a
`:coordinate-appointment-scheduling` proposal whose rationale says
"no 診断 (diagnosis) judgment" -- itself containing the banned
substring "診断") makes the governor's maximally-conservative scope
filter HARD-hold the op unconditionally, every time, regardless of how
clean the actual request is. This was caught empirically during this
repo's own build (confirmed via `clojure -M:dev:test` failures before
the fix, 0 failures after) -- `vetops.advisor`'s five proposal
generators describe what IS being requested, not a negated list of
banned terms, avoiding the trap rather than merely working around it
after the fact.

### 6. `phase.cljc` must reference the SAME op names `governor.cljc` allows

A drive-by-adaptation risk this repo's own build also caught
empirically: an op name typo'd differently between `governor.cljc`'s
allowlist and `phase.cljc`'s phase tables silently strands that op --
`phase/can-operate?` returns `false` for it at every phase, so it can
never execute through the actor even though the governor would allow
it. `vetops.phase`'s `:coordinate-appointment-scheduling`/
`:coordinate-boarding-assignment`/`:coordinate-supply-request`/
`:schedule-staff-shift-proposal` op names are verified identical to
`vetops.governor/allowed-ops` by `test/vetops/phase_test.clj`'s
`phase-3-auto-commits-clean-ops`.

### 7. No bespoke capability lib

Like `861`, this vertical's resource records are practice-specific
rather than a shared cross-operator data contract -- `vetops.*` runs on
the generic identity/forms/dmn/bpmn/audit-ledger stack only.

## Consequences

- (+) This repo and `cloud-itonami-isic-7500` are now genuinely
  non-duplicate: this repo is the practice's facility/logistics
  coordination layer; `7500` is the practice's clinical-treatment actor.
  Neither actor's governor can approve what the other actor's governor
  exists to gate.
- (+) `blueprint.edn` now self-identifies correctly
  (`:itonami.blueprint/id "cloud-itonami-isic-750"`,
  `:itonami.blueprint/robotics false`) and uses the same vector +
  `:db/id` shape this fleet's other coordination-only group repos use
  (`861`/`869`/`873`/`879`), distinguishing it at a glance from
  class-level repos' plain-map shape.
- (+) 42 tests / 122 assertions passing, `clojure -M:dev:run` demo
  clean (all four coordination ops auto-commit at phase 3, the safety
  concern always escalates, all HARD-hold scenarios hold and never
  reach a human), `docs/samples/operator-console.html` regenerated
  through the real actor and verified byte-identical across two
  consecutive runs (same discipline `hospitalops.render-html`
  established).
- (-) This repo now depends on a human reader knowing that ISIC 750 and
  7500 name the same real-world activity at the numeric-code level but
  intentionally different actor authority -- documented explicitly in
  README's "Group vs. class" section to make this legible rather than
  surprising.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep the repo as a relabeled copy of `7500` (just fix the id string) | ❌ | Would still claim clinical-treatment authority no other 3-digit group repo in this fleet has, and would give operators no reason to deploy both repos |
| Delete this repo, since group 750 has exactly one class | ❌ | Every other 3-digit/4-digit pair in this fleet keeps both repos even when the group has few classes; the coordination-only/full-clinical-workflow split is a real, useful authority boundary independent of how many classes subdivide the group |
| Model group 750 as a fleet-coordination layer across multiple hypothetical veterinary sub-verticals (equine, farm-animal, companion-animal specialty practices) | ❌ | ISIC does not subdivide 750 into multiple classes -- inventing sub-verticals ISIC itself does not recognize would misrepresent the classification this blueprint is grounded in |
