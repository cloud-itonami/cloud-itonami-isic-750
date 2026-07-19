(ns vetops.advisor
  "VetOpsAdvisor -- the *contained intelligence node* for the ISIC-750
  veterinary activities operations-coordination actor.

  It drafts exactly five kinds of back-office proposal from a closed
  allowlist: exam-room/appointment-slot scheduling, kennel/boarding-run
  assignment coordination, non-clinical consumable supply coordination,
  staff shift proposals, and facility safety-concern flagging.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a direct actuation -- every proposal's
  `:effect` is always `:propose`. Every output is censored downstream
  by `vetops.governor` before anything touches the SSoT.

  This advisor NEVER drafts diagnosis, treatment decisions, medication/
  vaccine/anesthesia administration, surgical or dental procedures,
  euthanasia decisions, or license/compliance-enforcement actions --
  those are permanently out of scope for this actor (and maximally
  conservatively scanned given veterinary practices' inherent clinical
  centrality for their animal patients), not merely un-implemented.
  `vetops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :resource-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-appointment-scheduling
  "Draft an exam-room/appointment-slot scheduling coordination
  proposal. Pure logistics: which physical room is available, slot
  scheduling. NEVER a diagnosis, triage-priority, or treatment
  decision."
  [_db {:keys [resource-id patch]}]
  {:op         :coordinate-appointment-scheduling
   :resource-id resource-id
   :summary    (str resource-id " の予約枠調整: " (pr-str (keys patch)))
   :rationale  "診察室・予約枠の物理的な可用性とスケジューリングのみを扱う純粋な事務調整"
   :cites      [resource-id]
   :effect     :propose
   :value      (merge {:resource-id resource-id} patch)
   :confidence 0.92})

(defn- propose-boarding-assignment
  "Draft a kennel/boarding-run assignment coordination proposal
  (which physical run is available, turnover scheduling -- never a
  clinical decision about whether an animal is fit to board)."
  [_db {:keys [resource-id patch]}]
  {:op         :coordinate-boarding-assignment
   :resource-id resource-id
   :summary    (str resource-id " のケネル/ボーディング割り当て調整: " (pr-str (keys patch)))
   :rationale  "ケネル・ボーディングランの物理的な可用性とターンオーバースケジューリングのみを扱う純粋な事務調整"
   :cites      [resource-id]
   :effect     :propose
   :value      (merge {:resource-id resource-id} patch)
   :confidence 0.88})

(defn- propose-supply-request
  "Draft a NON-CLINICAL consumable supply request coordination
  (bedding, cleaning supplies, office/reception supplies --
  ABSOLUTELY NEVER medication, vaccines, anesthetics, controlled
  substances, or any clinical/medical supplies)."
  [_db {:keys [resource-id patch]}]
  {:op         :coordinate-supply-request
   :resource-id resource-id
   :summary    (str resource-id " に関連する非臨床消耗品リクエスト: " (pr-str (keys patch)))
   :rationale  "寝具・清掃用品・受付用品など事務・清掃系消耗品の調達調整のみを扱う純粋な事務手配"
   :cites      [resource-id]
   :effect     :propose
   :value      (merge {:resource-id resource-id} patch)
   :confidence 0.90})

(defn- propose-staff-shift
  "Draft a staff-shift roster PROPOSAL only (never a binding clinical
  staffing or coverage-adequacy decision). Actual shift finalization
  is always done by practice managers."
  [_db {:keys [resource-id patch]}]
  {:op         :schedule-staff-shift-proposal
   :resource-id resource-id
   :summary    (str resource-id " に関連するスタッフシフト提案: " (pr-str (keys patch)))
   :rationale  "行政スタッフのシフト割り当て提案のみ。確定は人間の practice manager が判断する。臨床スタッフ配置判定なし。"
   :cites      [resource-id]
   :effect     :propose
   :value      (merge {:resource-id resource-id} patch)
   :confidence 0.86})

(defn- propose-safety-concern
  "Surface a facility/operational safety concern (equipment
  malfunction, kennel-security/animal-escape hazard, slip/fall hazard)
  for HUMAN triage. This op ALWAYS escalates in `vetops.governor` --
  never auto-committed at any phase -- regardless of how confident the
  advisor is that the concern is real. CRITICAL: this flags
  FACILITY/OPERATIONAL safety, NOT patient-safety/clinical-emergency
  (which must go through actual veterinary clinical staff)."
  [_db {:keys [resource-id patch]}]
  {:op         :flag-safety-concern
   :resource-id resource-id
   :summary    (str resource-id " に関連する施設安全懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "施設・機器の安全上の観察事実の報告。常に人間の確認・対応が必要。患者の臨床上の懸念ではない。"
   :cites      [resource-id]
   :effect     :propose
   :value      (merge {:resource-id resource-id} patch)
   :confidence (or (:confidence patch) 0.84)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :coordinate-appointment-scheduling (propose-appointment-scheduling _db request)
                   :coordinate-boarding-assignment (propose-boarding-assignment _db request)
                   :coordinate-supply-request (propose-supply-request _db request)
                   :schedule-staff-shift-proposal (propose-staff-shift _db request)
                   :flag-safety-concern (propose-safety-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use. This injects VETERINARY-SPECIFIC scope violations.
    (if out-of-scope?
      (update proposal :rationale str " -- diagnosis suspected cardiomyopathy, treatment plan includes anesthesia and surgical procedure, prescribe controlled substance for pain management")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :resource-id (:resource-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
