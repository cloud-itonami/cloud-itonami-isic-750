(ns veterinary.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean case through
  intake -> jurisdiction licensing assessment -> credential screening
  -> treatment-administration proposal (always escalates) -> human
  approval -> commit, then shows five HARD holds (a jurisdiction with
  no spec-basis, a proposed treatment on the patient's own
  contraindication list, a food-producing animal whose planned-harvest
  timeline leaves insufficient time for the drug's withdrawal period,
  a lapsed veterinarian license, and a double administration of an
  already-treated case) that never reach a human at all, and prints
  the audit ledger + the draft treatment-administration records."
  (:require [langgraph.graph :as g]
            [veterinary.store :as store]
            [veterinary.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :licensed-veterinarian :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== case/intake case-1 (JPN, bovine, clean; 30 days until harvest, 21-day withdrawal period) ==")
    (println (exec! actor "t1" {:op :case/intake :subject "case-1"
                                :patch {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)"}} operator))

    (println "== jurisdiction/assess case-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator))
    (println (approve! actor "t2"))

    (println "== credential/screen case-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :credential/screen :subject "case-1"} operator))
    (println (approve! actor "t3"))

    (println "== treatment/administer case-1 (always escalates -- actuation/administer-treatment) ==")
    (let [r (exec! actor "t4" {:op :treatment/administer :subject "case-1"} operator)]
      (println r)
      (println "-- human licensed veterinarian approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess case-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "case-2" :no-spec? true} operator))

    (println "== jurisdiction/assess case-3 (escalates -- human approves; sets up the contraindication test) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "case-3"} operator))
    (println (approve! actor "t6"))

    (println "== treatment/administer case-3 (proposed treatment :penicillin is in the patient's own contraindication set -> HARD hold) ==")
    (println (exec! actor "t7" {:op :treatment/administer :subject "case-3"} operator))

    (println "== jurisdiction/assess case-5 (escalates -- human approves; sets up the withdrawal-period test) ==")
    (println (exec! actor "t8" {:op :jurisdiction/assess :subject "case-5"} operator))
    (println (approve! actor "t8"))

    (println "== treatment/administer case-5 (10 days until harvest < 21-day withdrawal period -> HARD hold) ==")
    (println (exec! actor "t9" {:op :treatment/administer :subject "case-5"} operator))

    (println "== credential/screen case-4 (lapsed veterinarian license -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :credential/screen :subject "case-4"} operator))

    (println "== treatment/administer case-1 AGAIN (double-administration of an already-treated case -> HARD hold) ==")
    (println (exec! actor "t11" {:op :treatment/administer :subject "case-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft treatment-administration records ==")
    (doseq [r (store/treatment-history db)] (println r))))
