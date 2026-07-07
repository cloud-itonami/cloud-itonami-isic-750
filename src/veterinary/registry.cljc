(ns veterinary.registry
  "Pure-function treatment-administration record construction -- an
  append-only veterinary book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a treatment-administration reference number
  -- every practice/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `veterinary.facts` uses.

  `treatment-contraindicated?` reuses `clinic.registry/treatment-
  contraindicated?`'s set-membership/conflict shape verbatim for the
  veterinary domain (a proposed treatment must not appear in the
  patient's own recorded contraindication set) -- the same real
  clinical-safety concept applies to animal patients as to human
  patients.

  `withdrawal-period-insufficient?` is a NEW real-world regulatory
  concept for this fleet: for a food-producing animal, a drug's
  required withdrawal period (the time that must elapse after
  treatment before the animal or its products may enter the food
  supply) must fully elapse before the animal's planned harvest/
  slaughter date. This is the FIRST check in this fleet to model a
  food-safety/temporal-sufficiency concept -- see its own docstring
  for the honest simplification it makes vs. a full withdrawal-
  interval/residue-testing regime. Arithmetically it reuses this
  fleet's MINIMUM-threshold pure-ground-truth-recompute shape
  (`marketadmin.registry/listing-standard-met?`/`registrar.registry/
  credits-sufficient?`), but is the first application of that shape
  gated on a boolean type tag (`:food-producing?`) the same way
  `accounting.governor/wrong-engagement-type-violations` gates its own
  checks -- a deliberate combination of two previously-separate
  patterns.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real veterinary-practice-management system. It builds
  the RECORD a practice would keep, not the act of administering the
  treatment itself (that is `veterinary.operation`'s `:treatment/
  administer`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  treating veterinarian's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn treatment-contraindicated?
  "Does `case-record`'s own `:proposed-treatment` appear in its own
  `:contraindications` set? A pure ground-truth check against the
  case's own permanent fields -- reuses `clinic.registry/treatment-
  contraindicated?`'s set-membership/conflict shape verbatim for the
  veterinary domain (this R0 does not model interaction severity,
  dosage-dependent contraindications, or cross-drug interactions --
  only whether the proposed treatment itself is a member of the
  patient's own recorded contraindication set)."
  [{:keys [proposed-treatment contraindications]}]
  (contains? (set contraindications) proposed-treatment))

(defn withdrawal-period-insufficient?
  "For a food-producing animal ONLY (`:food-producing?` true), does the
  case's own `:days-until-planned-harvest` fall below its own
  `:withdrawal-period-days`? A pure ground-truth check against the
  case's own permanent fields -- see ns docstring for the honest
  simplification this makes vs. a full withdrawal-interval/residue-
  testing regime (this R0 models a single flat withdrawal-period
  figure per case, not species/drug/route-of-administration-specific
  withdrawal tables or residue-testing verification). Deliberately
  guards on `:food-producing?` FIRST, before touching the harvest/
  withdrawal fields -- a companion animal has neither field populated,
  and the SAME NullPointerException risk `cloud-itonami-isic-6920`'s
  ADR-0001 documents (a pure ground-truth recompute whose inputs are
  only meaningful for a SUBSET of an entity's possible states must
  guard on the discriminating flag before touching the type-specific
  fields) applies here."
  [{:keys [food-producing? days-until-planned-harvest withdrawal-period-days]}]
  (and food-producing?
       (< days-until-planned-harvest withdrawal-period-days)))

(defn register-treatment
  "Validate + construct the CERTIFICATION registration DRAFT -- the
  veterinary practice's own legal act of administering a real
  treatment, prescription or procedure. Pure function -- does not
  touch any real veterinary-practice-management system; it builds the
  RECORD a practice would keep. `veterinary.governor` independently
  re-verifies the case's own contraindication set and food-safety
  withdrawal sufficiency, and blocks a double-administration of the
  same case, before this is ever allowed to commit."
  [case-id jurisdiction sequence]
  (when-not (and case-id (not= case-id ""))
    (throw (ex-info "treatment: case_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "treatment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "treatment: sequence must be >= 0" {})))
  (let [treatment-number (str (str/upper-case jurisdiction) "-TX-" (zero-pad sequence 6))
        record {"record_id" treatment-number
                "kind" "treatment-administration-draft"
                "case_id" case-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "treatment_number" treatment-number
     "certificate" (unsigned-certificate "TreatmentAdministration" treatment-number treatment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
