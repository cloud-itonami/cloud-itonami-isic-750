# cloud-itonami-isic-750

**Veterinary activities** — ISIC Rev.4 group 750.

An operations-coordination-only actor for veterinary-practice back-office
administration, behind an independent Governor that earns advisor trust through
structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Group vs. class: how this repo relates to `cloud-itonami-isic-7500`

ISIC group **750** ("Veterinary activities") subdivides into exactly **one**
class, **7500** ("Veterinary activities") — division 75 is one of the small
number of ISIC divisions that bottoms out in a single group/single class (no
further split the way, say, division 85's education groups split into several
4-digit classes each). That means this repo and
[`cloud-itonami-isic-7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500)
describe the *same* real-world activity at the numeric-code level — but this
fleet's own established pattern for a 3-digit **group** repo is not "restate the
class repo with the numbers changed"; it is a **narrower-authority, coordination-
only actor sitting alongside the full clinical-workflow class actor**, the same
split every other 3-digit/4-digit pair in this fleet uses (e.g.
[`cloud-itonami-isic-861`](https://github.com/cloud-itonami/cloud-itonami-isic-861)'s
`hospitalops.*` — administrative/facility coordination only — vs.
[`cloud-itonami-isic-8610`](https://github.com/cloud-itonami/cloud-itonami-isic-8610)'s
`hospital.*` — the full clinical treatment-administration workflow):

| | `cloud-itonami-isic-750` (this repo) | `cloud-itonami-isic-7500` |
|---|---|---|
| Scope | **Coordination only** — exam-room/appointment-slot scheduling, kennel/boarding-run assignment, non-clinical supply requests, staff shift proposals, facility safety-concern flagging | Full clinical workflow — case intake, jurisdiction licensing assessment, clinician-credential screening, treatment administration |
| Namespace | `vetops.*` | `veterinary.*` |
| Governor | VetOps Governor — 3 HARD checks, MAXIMALLY conservative scope exclusion (any diagnosis/treatment/medication/vaccine/anesthesia/surgical/euthanasia content is a permanent block) | Veterinary Care Governor — 5 HARD checks gating the actual treatment-administration act itself |
| Real-world actuation | None — every op is `:propose`-only administrative coordination, nothing here ever reaches "a treatment was administered" | One — administering a real treatment/prescription/procedure, always human-gated |
| `:itonami.blueprint/robotics` | `false` | `true` |

Both repos are legitimate, non-duplicate members of this fleet: this repo is the
practice's facility/logistics coordination layer; `cloud-itonami-isic-7500` is the
practice's clinical-treatment actor. Neither repo needs the other to function, and
neither repo's governor can approve what the other actor's governor exists to gate.

## Features

- **Closed proposal-op allowlist**: `coordinate-appointment-scheduling`,
  `coordinate-boarding-assignment`, `coordinate-supply-request`,
  `schedule-staff-shift-proposal`, `flag-safety-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Resource verified** — target exam-room/kennel/bay must exist AND be
     registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — diagnosis, treatment, medication/vaccine/anesthesia
     administration, surgical/dental procedures, euthanasia decisions, controlled
     substances, and veterinarian-license/compliance-enforcement actions are
     permanently blocked.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: appointment-slot scheduling only (approval-gated)
  - Phase 2: + boarding assignment, supply request, staff shift proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (safety concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:dev:run

# Regenerate docs/samples/operator-console.html from the real actor
clojure -M:dev:render-html
```

## Test suite

- `test/vetops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/vetops/governor_contract_test.clj` — full-graph integration tests
- `test/vetops/advisor_test.clj` — proposal-generator shape/content tests
- `test/vetops/phase_test.clj` — rollout-phase gating tests
- `test/vetops/store_contract_test.clj` — `Store` protocol contract tests

## Layout

| File | Role |
|---|---|
| `src/vetops/store.cljc` | **Store** protocol — `MemStore` + append-only audit ledger + coordination log, keyed by string `:resource-id` |
| `src/vetops/advisor.cljc` | **VetOpsAdvisor** — closed five-op proposal generator, `:effect` always `:propose` |
| `src/vetops/governor.cljc` | **VetOps Governor** — 3 HARD checks + 1 always-escalate gate |
| `src/vetops/phase.cljc` | **Phase 0→3** — read-only → assisted scheduling → assisted coordination → supervised auto |
| `src/vetops/operation.cljc` | **OperationActor** — langgraph-clj StateGraph |
| `src/vetops/sim.cljc` | demo driver |
| `src/vetops/render_html.clj` | build-time renderer for `docs/samples/operator-console.html` |
| `test/vetops/*_test.clj` | governor unit + contract · advisor · phase · store contract |

## Open business

This repository is not only source code. It is a public, forkable business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, VetOps Governor, coordination records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service |
| Trust controls | Governance, security reporting, scope-exclusion invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the full
architecture and decision record.

## License

AGPL-3.0-or-later. See LICENSE for details.

## Contributing

See CONTRIBUTING.md for guidelines.

## Code of Conduct

See CODE_OF_CONDUCT.md.

## Security

See SECURITY.md for vulnerability reporting.
