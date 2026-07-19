# Contributing

`cloud-itonami-isic-750` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

This repo holds the business blueprint and operator contracts. See
`kotoba-lang/industry` for the technology-stack resolution.

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for any capability-layer change.

## Rules

- Do not commit real customer records, credentials, or personal data.
- Keep this actor's scope to administrative/facility coordination only --
  diagnosis, treatment, medication/vaccine/anesthesia administration,
  surgical/dental procedures and euthanasia decisions belong behind
  `cloud-itonami-isic-7500`'s Veterinary Care Governor, never this repo's
  VetOps Governor.
- Treat scope-exclusion as high-risk: add tests for any new op or any
  change to `vetops.governor/scope-excluded-terms`.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
