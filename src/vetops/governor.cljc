(ns vetops.governor
  "VetOpsGovernor -- the independent compliance layer for ISIC-750
  veterinary activities operations coordination. The advisor has no
  notion of whether a resource is actually registered and verified,
  whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area, so this MUST
  be a separate system able to *reject* a proposal and fall back to
  HOLD.

  This actor's scope is deliberately narrow -- ADMINISTRATIVE/FACILITY
  COORDINATION ONLY (exam-room/appointment-slot scheduling, kennel/
  boarding-run assignment logistics, non-clinical consumable supply
  coordination, staff shift proposals, facility safety-concern
  flagging). It NEVER performs or authorizes:
    - diagnosis, clinical assessment, or treatment/care-plan decisions
    - medication, vaccine, or anesthetic administration or dosing
    - surgical or dental procedures
    - euthanasia decisions
    - controlled-substance handling
    - veterinarian-license/compliance-enforcement actions
    - any clinical-authority overrides

  A veterinary practice is inherently a clinical setting for its
  patients, so scope exclusions are MAXIMALLY CONSERVATIVE -- any
  clinical/animal-care content whatsoever (even phrased as a \"safety
  concern\") is a HARD block.

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Resource unverified       -- the target exam-room/kennel/bay
                                   resource record must exist AND be
                                   independently confirmed
                                   `:registered?`/`:verified?` in the
                                   store before ANY proposal for it may
                                   commit or even escalate. Never
                                   trusts a proposal's own claim about
                                   the resource -- re-derived from the
                                   resource's own store record, the
                                   same 'ground truth, not self-report'
                                   discipline every sibling actor's
                                   governor uses.
    2. Effect not :propose      -- every proposal's `:effect` MUST be
                                   `:propose`. Any other effect value
                                   is, by construction, a claim to
                                   directly actuate/commit outside
                                   governance -- HARD block, not merely
                                   low-confidence.
    3. Scope exclusion          -- ANY proposal (regardless of op)
                                   whose op, rationale, summary,
                                   citations or draft value touches
                                   diagnosis/treatment/medication/
                                   vaccine/anesthesia/surgical/
                                   euthanasia/license-enforcement
                                   territory is a HARD, PERMANENT
                                   block. Veterinary practices are
                                   clinically central for their animal
                                   patients, so MAXIMALLY-CONSERVATIVE
                                   scope exclusions apply.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-safety-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `vetops.phase` independently agrees: `:flag-safety-concern` is never
  a member of any phase's `:auto` set either -- two layers, not one."
  (:require [clojure.string :as str]
            [vetops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist for veterinary-practice
  ADMINISTRATIVE/FACILITY COORDINATION ONLY. An op outside this set is
  a scope violation by construction."
  #{:coordinate-appointment-scheduling :coordinate-boarding-assignment
    :coordinate-supply-request :schedule-staff-shift-proposal
    :flag-safety-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area. Veterinary practices are
  clinically central for their animal patients, so scope exclusions
  are MAXIMALLY CONSERVATIVE. Covers diagnosis, treatment, medication/
  vaccine/anesthesia administration, surgical/dental procedures,
  euthanasia, controlled substances, and license/compliance
  enforcement. Scanned across the proposal's op/summary/rationale/
  cites/value, never trusting the advisor's own framing of its
  intent."
  ;; Diagnosis & clinical assessment
  ["diagnosis" "diagnos" "診断" "clinical assessment" "clinical-assessment"
   "臨床評価" "evaluate patient" "physical exam finding"
   ;; Treatment & care planning
   "treatment" "treatment plan" "treatment-plan" "care plan" "care-plan"
   "ケアプラン" "therapeutic" "therapy plan" "medical decision" "治療"
   ;; Medication, vaccine & anesthesia
   "medicatio" "薬" "dosing" "処方" "prescription" "prescribe" "rx"
   "pharma" "drug administration" "vaccine" "vaccination" "ワクチン"
   "inject" "injection" "anesthesia" "anesthetic" "sedation" "sedate"
   "麻酔" "controlled substance" "scheduled drug" "規制薬物"
   ;; Surgical & dental procedures
   "surgery" "surgical" "spay" "neuter" "dental procedure" "procedure"
   "手術"
   ;; Euthanasia
   "euthanasia" "euthanize" "put down" "安楽死"
   ;; License / compliance enforcement
   "license suspension" "license-suspension" "compliance enforcement"
   "compliance-enforcement" "investigat" "complaint" "veterinary board"
   "animal welfare violation" "cruelty investigation" "違反" "通報"])

;; ----------------------------- checks -----------------------------

(defn- resource-unverified-violations
  "The target resource must exist AND be independently `:registered?`/
  `:verified?` in the store -- never trust the proposal's own
  `:resource-id` claim without a store lookup."
  [{:keys [resource-id]} st]
  (let [r (store/resource st resource-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :resource-unverified
        :detail (str resource-id " は未登録または未検証の施設リソース -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches diagnosis/treatment/medication/
  vaccine/anesthesia/surgical/euthanasia/license-enforcement
  territory, regardless of confidence or how clean every other check
  is. Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "診断/治療/投薬・ワクチン・麻酔/手術/安楽死/免許・コンプライアンス執行の領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a VetOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [resource-id (or (:resource-id proposal) (:resource-id request))
        hard (into []
                   (concat (resource-unverified-violations {:resource-id resource-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
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
   :resource-id (:resource-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
