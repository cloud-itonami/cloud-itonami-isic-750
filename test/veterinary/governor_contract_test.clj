(ns veterinary.governor-contract-test
  "The governor contract as executable tests -- the veterinary analog
  of `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`.
  The single invariant under test:

    VetOps-LLM never administers a treatment the Veterinary Care
    Governor would reject, `:treatment/administer` NEVER auto-commits
    at any phase, `:case/intake` (no direct clinical risk) MAY auto-
    commit when clean, and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [veterinary.store :as store]
            [veterinary.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :licensed-veterinarian :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :case/intake :subject "case-1"
                   :patch {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Farm bovine #A-12 (owner: Sakura Ranch)" (:patient (store/case-record db "case-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "case-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "case-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "case-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "case-1")) "no assessment written"))))

(deftest treatment-administer-without-assessment-is-held
  (testing "treatment/administer before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :treatment/administer :subject "case-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest contraindicated-treatment-is-held
  (testing "a proposed treatment that appears on the patient's own contraindication list -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "case-3")
          res (exec-op actor "t5" {:op :treatment/administer :subject "case-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:contraindicated} (-> (store/ledger db) last :basis)))
      (is (empty? (store/treatment-history db))))))

(deftest withdrawal-period-insufficient-is-held
  (testing "a food-producing animal whose planned-harvest timeline leaves insufficient time for the drug's withdrawal period -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "case-5")
          res (exec-op actor "t6" {:op :treatment/administer :subject "case-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:withdrawal-period-insufficient} (-> (store/ledger db) last :basis)))
      (is (empty? (store/treatment-history db))))))

(deftest credential-not-current-is-held-and-unoverridable
  (testing "a lapsed veterinarian license on a case -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :credential/screen :subject "case-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:credential-not-current} (-> (store/ledger db) first :basis)))
      (is (nil? (store/credential-of db "case-4")) "no clearance written"))))

(deftest treatment-administer-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, in-tolerance case still ALWAYS interrupts for human approval -- actuation/administer-treatment is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "case-1")
          r1 (exec-op actor "t9" {:op :treatment/administer :subject "case-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, treatment record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:treated? (store/case-record db "case-1"))))
          (is (= 1 (count (store/treatment-history db))) "one draft treatment record")))))
  (testing "reject -> hold, nothing treated"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "case-1")
          _ (exec-op actor "t10" {:op :treatment/administer :subject "case-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t10" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/treatment-history db)) "nothing treated on reject"))))

(deftest treatment-administer-double-administration-is-held
  (testing "administering a treatment to the same case twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "case-1")
          _ (exec-op actor "t11a" {:op :treatment/administer :subject "case-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :treatment/administer :subject "case-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-treated} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/treatment-history db))) "still only the one earlier treatment"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :case/intake :subject "case-1"
                          :patch {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "case-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
