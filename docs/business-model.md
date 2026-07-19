# Business Model: Veterinary Activities — Operations Coordination

## Classification

- Repository: `cloud-itonami-isic-750`
- ISIC Rev.4: `750` (group; the single class `7500` -- see `cloud-itonami-isic-7500`)
- Activity: veterinary-practice back-office administrative/facility coordination --
  exam-room/appointment-slot scheduling, kennel/boarding-run assignment,
  non-clinical supply coordination, staff shift proposals, facility
  safety-concern flagging. Deliberately excludes diagnosis, treatment and
  care of animals -- that is `cloud-itonami-isic-7500`'s scope.
- Social impact: operational efficiency, animal-welfare support, facility
  safety, accessibility

## Customer

- independent veterinary practices
- cooperative animal-care clinics
- community/shelter veterinary programs

## Offer

- exam-room/appointment-slot scheduling coordination
- kennel/boarding-run assignment coordination
- non-clinical supply-request coordination
- staff shift proposals
- facility safety-concern flagging
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per practice
- support: monthly retainer with SLA
- migration: import from an incumbent veterinary-practice scheduling system
- per-resource fee

## Trust Controls

- a resource (exam room, kennel/boarding run, procedure bay) must be
  independently registered AND verified before any proposal for it may
  commit or escalate
- any proposal whose content touches diagnosis, treatment, medication/
  vaccine/anesthesia administration, surgical/dental procedures, euthanasia
  decisions, controlled substances, or veterinarian-license/compliance
  enforcement is a permanent, un-overridable HARD block -- this actor never
  performs or authorizes that territory, by construction
- a facility safety concern always escalates to a human, regardless of
  confidence or how clean the proposal otherwise is
- every proposal, hold, escalation and commit path is auditable
- emergency manual override paths remain outside LLM control
