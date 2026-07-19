# Operator Guide

## First Deployment

1. Register the practice's location(s), resources (exam rooms, kennel/boarding
   runs, procedure bays) and responsible practice manager.
2. Import the resource directory (which rooms/runs exist, their
   registered/verified status).
3. Run read-only validation of existing records against this blueprint's
   contracts.
4. Configure the VetOps Governor's hold/escalation policy.
5. Publish a dry-run operation and audit export.

## Minimum Production Controls

- a resource must be independently registered AND verified before any
  proposal targeting it may commit or escalate
- flagging a facility safety concern always requires a human sign-off
- audit export for every hold, approval and commit
- backup manual process for governor/system outage
- diagnosis, treatment, medication/vaccine/anesthesia administration,
  surgical/dental procedures and euthanasia decisions are permanently out of
  scope for this actor -- route those through
  `cloud-itonami-isic-7500`'s Veterinary Care Governor instead

## Certification

Certified operators must prove resource-record integrity, governor
independence, evidence-backed reporting and human review for every
high-stakes action.
