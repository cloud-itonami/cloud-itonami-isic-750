(ns veterinary.store
  "SSoT for the veterinary actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/veterinary/store_contract_test.clj), which is the whole point:
  the actor, the Veterinary Care Governor and the audit ledger never
  know which SSoT they run on.

  Like `clinic.store`'s simpler entities, a CASE is acted on directly
  by the ONE actuation op -- no dynamically-filed sub-record, and the
  double-administration guard checks a dedicated `:treated?` boolean
  rather than a `:status` value, the same discipline `accounting.
  governor`'s/`marketadmin.governor`'s/`testlab.governor`'s/`clinic.
  governor`'s/`registrar.governor`'s/`wagering.governor`'s guards
  establish.

  The ledger stays append-only on every backend: 'which case was
  screened for a current clinician license, which treatment was
  administered, on what jurisdictional basis, approved by whom' is
  always a query over an immutable log -- the audit trail an animal
  owner trusting a practice needs, and the evidence an operator needs
  if a treatment is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [veterinary.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (case-record [s id])
  (all-cases [s])
  (credential-of [s case-id] "committed credential screening verdict for a case, or nil")
  (assessment-of [s case-id] "committed jurisdiction accreditation assessment, or nil")
  (ledger [s])
  (treatment-history [s] "the append-only treatment-administration history (veterinary.registry drafts)")
  (next-sequence [s jurisdiction] "next treatment-administration-number sequence for a jurisdiction")
  (case-already-treated? [s case-id] "has this case already been treated?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-cases [s cases] "replace/seed the case directory (map id->case)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained case set so the actor + tests run offline."
  []
  {:cases
   {"case-1" {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)" :species :bovine
               :proposed-treatment :penicillin-g-procaine :contraindications #{}
               :food-producing? true :days-until-planned-harvest 30 :withdrawal-period-days 21
               :clinician-license-current? true :treated? false :jurisdiction "JPN" :status :intake}
    "case-2" {:id "case-2" :patient "Rex (owner: Atlantis Doe)" :species :canine
               :proposed-treatment :amoxicillin :contraindications #{}
               :food-producing? false :days-until-planned-harvest nil :withdrawal-period-days nil
               :clinician-license-current? true :treated? false :jurisdiction "ATL" :status :intake}
    "case-3" {:id "case-3" :patient "ハチ公 (owner: 鈴木一郎)" :species :canine
               :proposed-treatment :penicillin :contraindications #{:penicillin}
               :food-producing? false :days-until-planned-harvest nil :withdrawal-period-days nil
               :clinician-license-current? true :treated? false :jurisdiction "JPN" :status :intake}
    "case-4" {:id "case-4" :patient "ポチ (owner: 田中花子)" :species :canine
               :proposed-treatment :amoxicillin :contraindications #{}
               :food-producing? false :days-until-planned-harvest nil :withdrawal-period-days nil
               :clinician-license-current? false :treated? false :jurisdiction "JPN" :status :intake}
    "case-5" {:id "case-5" :patient "Farm bovine #B-07 (owner: 佐藤牧場)" :species :bovine
               :proposed-treatment :penicillin-g-procaine :contraindications #{}
               :food-producing? true :days-until-planned-harvest 10 :withdrawal-period-days 21
               :clinician-license-current? true :treated? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- administer-treatment!
  "Backend-agnostic `:case/mark-treated` -- looks up the case via the
  protocol and drafts the treatment-administration record, and returns
  {:result .. :case-patch ..} for the caller to persist."
  [s case-id]
  (let [c (case-record s case-id)
        seq-n (next-sequence s (:jurisdiction c))
        result (registry/register-treatment case-id (:jurisdiction c) seq-n)]
    {:result result
     :case-patch {:treated? true
                  :treatment-number (get result "treatment_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (case-record [_ id] (get-in @a [:cases id]))
  (all-cases [_] (sort-by :id (vals (:cases @a))))
  (credential-of [_ id] (get-in @a [:credential id]))
  (assessment-of [_ case-id] (get-in @a [:assessments case-id]))
  (ledger [_] (:ledger @a))
  (treatment-history [_] (:treatments @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (case-already-treated? [_ case-id] (boolean (get-in @a [:cases case-id :treated?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (swap! a update-in [:cases (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :credential/set
      (swap! a assoc-in [:credential (first path)] payload)

      :case/mark-treated
      (let [case-id (first path)
            {:keys [result case-patch]} (administer-treatment! s case-id)
            jurisdiction (:jurisdiction (case-record s case-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:cases case-id] merge case-patch)
                       (update :treatments registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-cases [s cases] (when (seq cases) (swap! a assoc :cases cases)) s))

(defn seed-db
  "A MemStore seeded with the demo case set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :credential {} :ledger [] :sequences {}
                           :treatments []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/credential payloads, ledger facts,
  contraindication sets, treatment records) are stored as EDN strings
  so `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:case/id                     {:db/unique :db.unique/identity}
   :assessment/case-id           {:db/unique :db.unique/identity}
   :credential/case-id            {:db/unique :db.unique/identity}
   :ledger/seq                     {:db/unique :db.unique/identity}
   :treatment/seq                   {:db/unique :db.unique/identity}
   :sequence/jurisdiction             {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- case->tx [{:keys [id patient species proposed-treatment contraindications food-producing?
                         days-until-planned-harvest withdrawal-period-days clinician-license-current?
                         treated? jurisdiction status treatment-number]}]
  (cond-> {:case/id id}
    patient                        (assoc :case/patient patient)
    species                          (assoc :case/species species)
    proposed-treatment                 (assoc :case/proposed-treatment proposed-treatment)
    contraindications                    (assoc :case/contraindications (enc contraindications))
    (some? food-producing?)                (assoc :case/food-producing? food-producing?)
    days-until-planned-harvest               (assoc :case/days-until-planned-harvest days-until-planned-harvest)
    withdrawal-period-days                     (assoc :case/withdrawal-period-days withdrawal-period-days)
    (some? clinician-license-current?)           (assoc :case/clinician-license-current? clinician-license-current?)
    (some? treated?)                               (assoc :case/treated? treated?)
    jurisdiction                                     (assoc :case/jurisdiction jurisdiction)
    status                                             (assoc :case/status status)
    treatment-number                                     (assoc :case/treatment-number treatment-number)))

(def ^:private case-pull
  [:case/id :case/patient :case/species :case/proposed-treatment :case/contraindications
   :case/food-producing? :case/days-until-planned-harvest :case/withdrawal-period-days
   :case/clinician-license-current? :case/treated? :case/jurisdiction :case/status :case/treatment-number])

(defn- pull->case [m]
  (when (:case/id m)
    {:id (:case/id m) :patient (:case/patient m) :species (:case/species m)
     :proposed-treatment (:case/proposed-treatment m)
     :contraindications (or (dec* (:case/contraindications m)) #{})
     :food-producing? (boolean (:case/food-producing? m))
     :days-until-planned-harvest (:case/days-until-planned-harvest m)
     :withdrawal-period-days (:case/withdrawal-period-days m)
     :clinician-license-current? (boolean (:case/clinician-license-current? m))
     :treated? (boolean (:case/treated? m))
     :jurisdiction (:case/jurisdiction m) :status (:case/status m)
     :treatment-number (:case/treatment-number m)}))

(defrecord DatomicStore [conn]
  Store
  (case-record [_ id]
    (pull->case (d/pull (d/db conn) case-pull [:case/id id])))
  (all-cases [_]
    (->> (d/q '[:find [?id ...] :where [?e :case/id ?id]] (d/db conn))
         (map #(pull->case (d/pull (d/db conn) case-pull [:case/id %])))
         (sort-by :id)))
  (credential-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?cid
                :where [?k :credential/case-id ?cid] [?k :credential/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ case-id]
    (dec* (d/q '[:find ?p . :in $ ?cid
                :where [?a :assessment/case-id ?cid] [?a :assessment/payload ?p]]
              (d/db conn) case-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (treatment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :treatment/seq ?s] [?e :treatment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (case-already-treated? [s case-id]
    (boolean (:treated? (case-record s case-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :case/upsert
      (d/transact! conn [(case->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/case-id (first path) :assessment/payload (enc payload)}])

      :credential/set
      (d/transact! conn [{:credential/case-id (first path) :credential/payload (enc payload)}])

      :case/mark-treated
      (let [case-id (first path)
            {:keys [result case-patch]} (administer-treatment! s case-id)
            jurisdiction (:jurisdiction (case-record s case-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(case->tx (assoc case-patch :id case-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:treatment/seq (count (treatment-history s)) :treatment/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-cases [s cases]
    (when (seq cases) (d/transact! conn (mapv case->tx (vals cases)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:cases ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [cases]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-cases s cases))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo case set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
