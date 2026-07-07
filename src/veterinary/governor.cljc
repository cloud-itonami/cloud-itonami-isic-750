(ns veterinary.governor
  "Veterinary Care Governor -- the independent compliance layer that
  earns the VetOps-LLM the right to commit. The LLM has no notion of
  jurisdictional veterinary-licensing law, whether a proposed
  treatment actually appears on a patient's own recorded
  contraindication list, whether a food-producing animal's own
  planned-harvest timeline actually leaves enough time for a drug's
  required withdrawal period to elapse, whether the treating
  veterinarian's own license is actually current, or when an act stops
  being a draft and becomes a real-world treatment administration, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD -- the veterinary analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Five checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete licensing evidence, a
  treatment that appears on the patient's own contraindication list, a
  food-safety withdrawal-period shortfall, a not-current veterinarian
  license, or administering a treatment to the same case twice). The
  confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `veterinary.phase`: for `:stake :actuation/administer-treatment` (a
  real treatment administration) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a
  human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`veterinary.
                                       facts`), or invent one? Like
                                       `credit.governor`'s/`marketadmin.
                                       governor`'s/`testlab.governor`'s/
                                       `clinic.governor`'s actuation
                                       ops, `:treatment/administer`
                                       acts directly on a pre-seeded
                                       case (see `veterinary.store`'s
                                       own docstring) -- there is no
                                       'case is missing' failure mode
                                       to guard against here.
    2. Evidence incomplete         -- for `:treatment/administer`, has
                                       the jurisdiction actually been
                                       assessed with a full licensing
                                       evidence checklist on file?
    3. Contraindicated             -- for `:treatment/administer`,
                                       INDEPENDENTLY recompute whether
                                       the case's own `:proposed-
                                       treatment` appears in its own
                                       `:contraindications` set
                                       (`veterinary.registry/
                                       treatment-contraindicated?`) --
                                       reuses `clinic.governor/
                                       contraindicated-violations`'s
                                       set-membership/conflict shape
                                       verbatim for the veterinary
                                       domain.
    4. Withdrawal period
       insufficient                  -- for `:treatment/administer`,
                                       INDEPENDENTLY recompute, for a
                                       food-producing animal ONLY,
                                       whether the case's own days
                                       until its planned harvest date
                                       fall below its own required
                                       withdrawal period
                                       (`veterinary.registry/
                                       withdrawal-period-insufficient?`)
                                       -- needs no proposal inspection
                                       or stored-verdict lookup at all.
                                       The FIRST check in this fleet to
                                       model a food-safety/temporal-
                                       sufficiency concept, and the
                                       first to combine a MINIMUM-
                                       threshold recompute (`marketadmin.
                                       governor`'s/`registrar.
                                       governor`'s shape) with a type-
                                       tag gate (`accounting.governor`'s
                                       shape) in a single check.
    5. Credential not current      -- reported by THIS proposal itself
                                       (a `:credential/screen` that just
                                       found a lapsed license), or
                                       already on file for the case
                                       (`:credential/screen`/
                                       `:treatment/administer`).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       `marketadmin.governor/
                                       surveillance-flag-unresolved-
                                       violations`/`testlab.governor/
                                       calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations` established -- the
                                       SEVENTH distinct application of
                                       this exact discipline.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:treatment/
                                       administer` (a REAL veterinary
                                       act) -> escalate.

  One more guard, double-administration prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-treated-violations` refuses to
  administer a treatment to the SAME case twice, off a dedicated
  `:treated?` fact (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s/`clinic.governor`'s/
  `registrar.governor`'s/`wagering.governor`'s guards establish,
  informed by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [veterinary.facts :as facts]
            [veterinary.registry :as registry]
            [veterinary.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Administering a real treatment, prescription or procedure is the ONE
  real-world actuation event this actor performs -- a single-member
  set, matching `cloud-itonami-isic-6511`'s/`6621`'s/`6629`'s/`6612`'s/
  `6492`'s/`7120`'s/`8620`'s single-actuation shape."
  #{:actuation/administer-treatment})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:treatment/administer`) proposal with
  no spec-basis citation is a HARD violation -- never invent a
  jurisdiction's veterinary-licensing requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :treatment/administer} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:treatment/administer`, the jurisdiction's required owner-
  consent/diagnostic-evidence/license-verification/treatment-plan
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (let [c (store/case-record st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction c) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(飼主同意記録/診断根拠引用書/免許確認記録等)が充足していない状態での治療実施提案"}]))))

(defn- contraindicated-violations
  "For `:treatment/administer`, INDEPENDENTLY recompute whether the
  case's own proposed treatment appears in its own recorded
  contraindication set via `veterinary.registry/treatment-
  contraindicated?` -- reuses `clinic.governor/contraindicated-
  violations`'s shape verbatim for the veterinary domain."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (let [c (store/case-record st subject)]
      (when (registry/treatment-contraindicated? c)
        [{:rule :contraindicated
          :detail (str subject " の提案治療(" (:proposed-treatment c)
                      ")が患者自身の禁忌リスト" (:contraindications c) "に含まれている")}]))))

(defn- withdrawal-period-insufficient-violations
  "For `:treatment/administer`, INDEPENDENTLY recompute, for a food-
  producing animal ONLY, whether the case's own days-until-planned-
  harvest fall below its own withdrawal-period-days via `veterinary.
  registry/withdrawal-period-insufficient?` -- needs no proposal
  inspection or stored-verdict lookup at all. The FIRST check in this
  fleet to model a food-safety/temporal-sufficiency concept."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (let [c (store/case-record st subject)]
      (when (registry/withdrawal-period-insufficient? c)
        [{:rule :withdrawal-period-insufficient
          :detail (str subject " の残存日数(" (:days-until-planned-harvest c)
                      ")が休薬期間(" (:withdrawal-period-days c) ")を下回っている")}]))))

(defn- credential-not-current-violations
  "A not-current veterinarian license -- reported by THIS proposal
  (e.g. a `:credential/screen` that itself just found a lapsed
  license), or already on file in the store for the case
  (`:credential/screen`/`:treatment/administer`) -- is a HARD, un-
  overridable hold. Evaluated UNCONDITIONALLY (not scoped to a
  specific op) so the screening op itself can HARD-hold on its own
  finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :not-current (get-in proposal [:value :verdict]))
        case-id (when (contains? #{:credential/screen :treatment/administer} op) subject)
        hit-on-file? (and case-id (= :not-current (:verdict (store/credential-of st case-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :credential-not-current
        :detail "獣医師の免許が最新でない状態での治療実施提案は進められない"}])))

(defn- already-treated-violations
  "For `:treatment/administer`, refuses to administer a treatment to
  the SAME case twice, off a dedicated `:treated?` fact (never a
  `:status` value) -- see ns docstring for why this sidesteps the
  status-lifecycle risk `cloud-itonami-isic-6492`'s ADR-0001
  documents."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (when (store/case-already-treated? st subject)
      [{:rule :already-treated
        :detail (str subject " は既に治療実施済み")}])))

(defn check
  "Censors a VetOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (contraindicated-violations request st)
                           (withdrawal-period-insufficient-violations request st)
                           (credential-not-current-violations request proposal st)
                           (already-treated-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
