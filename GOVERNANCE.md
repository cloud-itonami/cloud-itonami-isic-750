# Governance

`cloud-itonami-isic-750` is an OSS open-business blueprint for veterinary-practice
back-office ADMINISTRATIVE/FACILITY COORDINATION -- exam-room/appointment-slot
scheduling, kennel/boarding-run assignment, non-clinical supply coordination, staff
shift proposals and facility safety-concern flagging. It never touches diagnosis,
treatment, medication/vaccine/anesthesia administration, surgical/dental procedures
or euthanasia decisions -- see `cloud-itonami-isic-7500` (`veterinary.*`) for the
class-level actor that models the practice's actual clinical treatment-
administration workflow behind a licensed-veterinarian approval gate.
Governance covers both the capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the VetOps Governor remains independent of the advisor.
- hard policy violations (unregistered/unverified resource, non-`:propose`
  effect, scope-excluded clinical content) cannot be overridden by human
  approval.
- flagging a facility safety concern always escalates to a human -- never
  automated.
- every hold, approval and commit path is auditable.
- personal and customer data stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the VetOps Governor's policy checks
- mishandling customer data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
