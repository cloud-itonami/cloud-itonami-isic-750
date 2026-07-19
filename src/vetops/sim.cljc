(ns vetops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean appointment-
  scheduling coordination request through intake -> advise -> govern
  -> decide -> approval -> commit at phase 1 (assisted-scheduling,
  always approval), then re-runs the same op at phase 3
  (supervised-auto, clean + high confidence -> auto-commit), then a
  boarding-assignment request, supply-request coordination, and
  staff-shift-proposal (all auto-commit clean at phase 3), then a
  facility-safety-concern flag (ALWAYS escalates, at any phase --
  approve, then commit), then HARD-hold scenarios: an unregistered
  resource, a resource registered but not yet verified, a proposal
  whose own `:effect` is not `:propose`, and a proposal that has
  drifted into the permanently-excluded clinical/diagnosis/treatment
  scope."
  (:require [langgraph.graph :as g]
            [vetops.advisor :as advisor]
            [vetops.store :as store]
            [vetops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "practice-manager-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        manager-phase-1 {:actor-id "mgr-1" :actor-role :practice-manager :phase 1}
        manager-phase-3 {:actor-id "mgr-1" :actor-role :practice-manager :phase 3}
        actor (op/build db)]

    (println "== coordinate-appointment-scheduling exam-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                                  :patch {:status "available" :slot "2026-08-01T09:00"}} manager-phase-1)]
      (println r)
      (println "-- human practice manager approves --")
      (println (approve! actor "t1")))

    (println "\n== coordinate-appointment-scheduling exam-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                                  :patch {:status "booked" :owner "Sakura Tanaka"}} manager-phase-3))

    (println "\n== coordinate-boarding-assignment kennel-12 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :coordinate-boarding-assignment :resource-id "kennel-12"
                                  :patch {:animal "Rex" :check-in "2026-08-01" :check-out "2026-08-05"}} manager-phase-3))

    (println "\n== coordinate-supply-request exam-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-request :resource-id "exam-1"
                                  :patch {:item "exam table paper roll" :quantity 6 :urgency "routine"}} manager-phase-3))

    (println "\n== schedule-staff-shift-proposal exam-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t5" {:op :schedule-staff-shift-proposal :resource-id "exam-1"
                                  :patch {:staff-member "vet tech Nakamura" :shift "morning" :date "2026-08-02"}} manager-phase-3))

    (println "\n== flag-safety-concern kennel-12 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-safety-concern :resource-id "kennel-12"
                                 :patch {:concern "kennel latch mechanism loose, escape risk" :confidence 0.93}} manager-phase-3)]
      (println r)
      (println "-- human practice manager reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== coordinate-appointment-scheduling exam-999 (unregistered resource -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :coordinate-appointment-scheduling :resource-id "exam-999"
                                  :patch {:status "available"}} manager-phase-3))

    (println "\n== coordinate-appointment-scheduling exam-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :coordinate-appointment-scheduling :resource-id "exam-3"
                                  :patch {:status "available"}} manager-phase-3))

    (println "\n== coordinate-boarding-assignment kennel-12, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :coordinate-boarding-assignment :resource-id "kennel-12"
                                           :patch {:animal "guest"}} manager-phase-3)))

    (println "\n== coordinate-appointment-scheduling exam-1, advisor drifts into clinical scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                                   :out-of-scope? true
                                   :patch {}} manager-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
