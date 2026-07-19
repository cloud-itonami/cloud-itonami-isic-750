(ns vetops.store
  "SSoT for the ISIC-750 veterinary activities operations-COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  This actor coordinates the back-office operations of a veterinary
  practice: exam-room/appointment-slot scheduling, kennel/boarding-run
  assignment logistics, non-clinical consumable supply coordination
  (bedding, cleaning supplies, office/reception supplies -- NEVER
  medication, vaccines, anesthetics or controlled substances), staff
  shift proposals, and facility safety-concern flagging (equipment
  malfunction, kennel-security/animal-escape hazards). It never
  touches clinical decision-making: diagnosis, treatment/care plans,
  medication/vaccine/anesthesia administration, surgical or dental
  procedures, euthanasia decisions, or any veterinarian-license/
  compliance-enforcement action -- see `vetops.governor`'s
  `scope-excluded-terms`, a HARD, permanent, un-overridable block.

  This is the GROUP-level (ISIC 750, 'Veterinary activities') actor --
  deliberately narrower in authority than the CLASS-level
  `cloud-itonami-isic-7500` actor (`veterinary.*`), which models the
  practice's actual clinical treatment-administration workflow (case
  intake, jurisdiction licensing assessment, credential screening,
  treatment administration) behind a licensed-veterinarian approval
  gate. `vetops.*` never reaches that territory at all; it is the
  practice's facility/logistics coordination layer sitting alongside
  it, the same COORDINATION-ONLY vs. full-clinical-workflow split this
  fleet's other 3-digit-group/4-digit-class pairs use (e.g.
  `cloud-itonami-isic-861`'s `hospitalops.*` vs.
  `cloud-itonami-isic-8610`'s `hospital.*`).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `resources` directory keyed by `:resource-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified resource record must exist before ANY proposal
  for that resource may ever commit or escalate -- `vetops.governor`'s
  `resource-unverified-violations` re-derives this from the resource's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which resource a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (resource [s resource-id] "Registered resource record, or nil.
    Resource map: {:resource-id .. :location .. :resource-type ..
    :registered? bool :verified? bool}.")
  (all-resources [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-resources [s resources] "replace/seed the resource directory (map resource-id->resource)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained resource directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:resources
   {"exam-1" {:resource-id "exam-1" :location "Exam Room 1" :resource-type :exam-room
              :registered? true :verified? true}
    "kennel-12" {:resource-id "kennel-12" :location "Boarding Ward, Run 12" :resource-type :kennel
                 :registered? true :verified? true}
    "exam-3" {:resource-id "exam-3" :location "Exam Room 3 (intake)" :resource-type :exam-room
              :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (resource [_ resource-id] (get-in @a [:resources resource-id]))
  (all-resources [_] (sort-by :resource-id (vals (:resources @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-resources [s resources] (when (seq resources) (swap! a assoc :resources resources)) s))

(defn seed-db
  "A MemStore seeded with the demo resource directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `resources` map (resource-id string ->
  resource map) -- the primary test/dev entry point. `resources` may be empty
  (an unregistered-everywhere store)."
  [resources]
  (->MemStore (atom {:resources (or resources {}) :ledger [] :coordination-log []})))
