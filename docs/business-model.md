# Business Model: Veterinary activities

## Classification

- Repository: `cloud-itonami-isic-7500`
- ISIC Rev.5: `7500`
- Activity: veterinary activities -- diagnosis, treatment and care of animals by licensed veterinary professionals
- Social impact: professional standards, data sovereignty, transparent audit

## Customer

- independent veterinary practices
- cooperative animal-care clinics
- community/shelter veterinary programs

## Offer

- patient (animal) intake
- diagnosis/treatment-plan proposal
- prescription/procedure proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per practice
- support: monthly retainer with SLA
- migration: import from an incumbent veterinary-practice system
- per-visit fee

## Trust Controls

- no treatment, prescription or procedure is administered without human sign-off (a licensed veterinarian)
- a fabricated jurisdiction licensing citation, incomplete licensing
  evidence, a proposed treatment that appears on the patient's own
  recorded contraindication list, a food-producing animal's planned-
  harvest timeline leaving insufficient time for a drug's required
  withdrawal period, or a lapsed clinician license -- each forces a
  hold, not an override
- a case cannot be treated twice: a double-administration attempt is
  held off this actor's own case facts alone, with no upstream
  comparison needed
- every intake, assessment, screening and administration path is
  auditable
- emergency manual override paths remain outside LLM control
