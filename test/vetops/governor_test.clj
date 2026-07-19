(ns vetops.governor-test
  "Pure unit tests of `vetops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-
  test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [vetops.governor :as gov]
            [vetops.store :as store]))

(def exam-1 {:resource-id "exam-1" :location "Exam Room 1" :resource-type :exam-room :registered? true :verified? true})
(def exam-3 {:resource-id "exam-3" :location "Exam Room 3" :resource-type :exam-room :registered? true :verified? false})

(defn- clean-proposal [op resource-id]
  {:op op :resource-id resource-id :summary "s" :rationale "routine facility coordination"
   :cites [resource-id] :effect :propose :value {} :confidence 0.85})

(deftest resource-unregistered-is-hard
  (testing "no resource record at all -> HARD hold"
    (let [s (store/mem-store {"exam-1" exam-1})
          verdict (gov/check {} nil (clean-proposal :coordinate-appointment-scheduling "unknown-resource") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:resource-unverified} (map :rule (:violations verdict)))))))

(deftest resource-unverified-is-hard
  (testing "resource registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"exam-3" exam-3})
          verdict (gov/check {} nil (clean-proposal :coordinate-appointment-scheduling "exam-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:resource-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"exam-1" exam-1})
          verdict (gov/check {} nil (assoc (clean-proposal :coordinate-boarding-assignment "exam-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed five-op allowlist is a scope violation"
    (let [s (store/mem-store {"exam-1" exam-1})
          verdict (gov/check {} nil (clean-proposal :administer-treatment "exam-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest diagnosis-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches diagnosis/clinical scope is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"exam-1" exam-1})
          poisoned (assoc (clean-proposal :coordinate-appointment-scheduling "exam-1")
                          :rationale "diagnosis suspected cardiomyopathy, requires cardiology workup"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest treatment-content-is-hard
  (testing "a proposal touching treatment-plan/clinical-decision is HARD-blocked"
    (let [s (store/mem-store {"exam-1" exam-1})
          poisoned (assoc (clean-proposal :coordinate-appointment-scheduling "exam-1")
                          :rationale "initiate treatment plan and adjust therapy"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest medication-content-is-hard
  (testing "a proposal touching medication/vaccine/anesthesia content is HARD-blocked"
    (let [s (store/mem-store {"exam-1" exam-1})
          poisoned (assoc (clean-proposal :coordinate-boarding-assignment "exam-1")
                          :summary "administer vaccination and adjust anesthesia dosing"
                          :confidence 0.92)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest surgical-procedure-content-is-hard
  (testing "a proposal touching surgical/dental procedure is HARD-blocked"
    (let [s (store/mem-store {"exam-1" exam-1})
          poisoned (assoc (clean-proposal :coordinate-supply-request "exam-1")
                          :value {:note "schedule spay surgery for this patient"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest euthanasia-content-is-hard
  (testing "a proposal touching euthanasia decisions is HARD-blocked"
    (let [s (store/mem-store {"exam-1" exam-1})
          poisoned (assoc (clean-proposal :schedule-staff-shift-proposal "exam-1")
                          :summary "recommend euthanasia for this case")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-facility-safety-concern-is-not-scope-excluded
  (testing "flagging facility/operational safety concerns (kennel latch, equipment malfunction) as a FACILITY SAFETY CONCERN never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"exam-1" exam-1})
          concern (assoc (clean-proposal :flag-safety-concern "exam-1")
                         :value {:concern "kennel latch mechanism loose, floor cleaning in progress"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (facility/operational safety) is exactly what this op exists to surface"))))
