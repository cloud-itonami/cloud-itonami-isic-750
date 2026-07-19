(ns vetops.advisor-test
  "Unit tests of `vetops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [vetops.advisor :as adv]
            [vetops.store :as store]))

(def db (store/seed-db))

(deftest propose-appointment-scheduling-shape
  (testing "appointment-scheduling proposal has correct shape and fields"
    (let [p (adv/infer db {:op :coordinate-appointment-scheduling
                           :resource-id "exam-1"
                           :patch {:status "available" :slot "09:00"}})]
      (is (= :coordinate-appointment-scheduling (:op p)))
      (is (= "exam-1" (:resource-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :resource-id)))))

(deftest propose-boarding-assignment-shape
  (testing "boarding-assignment proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-boarding-assignment
                           :resource-id "kennel-12"
                           :patch {:animal "Rex" :check-in "2026-08-01"}})]
      (is (= :coordinate-boarding-assignment (:op p)))
      (is (= "kennel-12" (:resource-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-request-shape
  (testing "supply-request proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-request
                           :resource-id "exam-1"
                           :patch {:item "bedding" :quantity 2}})]
      (is (= :coordinate-supply-request (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-staff-shift-shape
  (testing "staff-shift proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staff-shift-proposal
                           :resource-id "exam-1"
                           :patch {:staff "vet tech Nakamura" :shift "morning"}})]
      (is (= :schedule-staff-shift-proposal (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest propose-safety-concern-shape
  (testing "safety-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-safety-concern
                           :resource-id "exam-1"
                           :patch {:concern "kennel latch loose"}})]
      (is (= :flag-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:coordinate-appointment-scheduling :coordinate-boarding-assignment :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :resource-id "exam-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:coordinate-appointment-scheduling :coordinate-boarding-assignment :coordinate-supply-request
                :schedule-staff-shift-proposal :flag-safety-concern]]
      (let [p (adv/infer db {:op op :resource-id "exam-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest out-of-scope-hook-injects-clinical-content
  (testing "the :out-of-scope? test hook injects clinical-territory content for governor-contract testing"
    (let [p (adv/infer db {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                           :out-of-scope? true :patch {}})]
      (is (re-find #"(?i)diagnosis|anesthesia|surgical|controlled substance" (:rationale p))))))
