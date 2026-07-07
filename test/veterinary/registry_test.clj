(ns veterinary.registry-test
  (:require [clojure.test :refer [deftest is]]
            [veterinary.registry :as r]))

;; ----------------------------- treatment-contraindicated? -----------------------------

(deftest not-contraindicated-when-treatment-absent-from-the-set
  (is (not (r/treatment-contraindicated? {:proposed-treatment :amoxicillin :contraindications #{}})))
  (is (not (r/treatment-contraindicated? {:proposed-treatment :amoxicillin :contraindications #{:penicillin}}))))

(deftest contraindicated-when-treatment-is-a-member-of-the-set
  (is (r/treatment-contraindicated? {:proposed-treatment :penicillin :contraindications #{:penicillin}}))
  (is (r/treatment-contraindicated? {:proposed-treatment :penicillin :contraindications #{:penicillin :sulfa}})))

;; ----------------------------- withdrawal-period-insufficient? -----------------------------

(deftest withdrawal-period-sufficient-when-enough-days-remain
  (is (not (r/withdrawal-period-insufficient? {:food-producing? true :days-until-planned-harvest 30 :withdrawal-period-days 21})))
  (is (not (r/withdrawal-period-insufficient? {:food-producing? true :days-until-planned-harvest 21 :withdrawal-period-days 21}))))

(deftest withdrawal-period-insufficient-when-not-enough-days-remain
  (is (r/withdrawal-period-insufficient? {:food-producing? true :days-until-planned-harvest 10 :withdrawal-period-days 21})))

(deftest withdrawal-period-check-never-fires-for-non-food-producing-animals
  (is (not (r/withdrawal-period-insufficient? {:food-producing? false :days-until-planned-harvest nil :withdrawal-period-days nil})))
  (is (not (r/withdrawal-period-insufficient? {:food-producing? nil :days-until-planned-harvest nil :withdrawal-period-days nil}))))

;; ----------------------------- register-treatment -----------------------------

(deftest treatment-is-a-draft-not-a-real-administration
  (let [result (r/register-treatment "case-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest treatment-assigns-treatment-number
  (let [result (r/register-treatment "case-1" "JPN" 7)]
    (is (= (get result "treatment_number") "JPN-TX-000007"))
    (is (= (get-in result ["record" "case_id"]) "case-1"))
    (is (= (get-in result ["record" "kind"]) "treatment-administration-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest treatment-validation-rules
  (is (thrown? Exception (r/register-treatment "" "JPN" 0)))
  (is (thrown? Exception (r/register-treatment "case-1" "" 0)))
  (is (thrown? Exception (r/register-treatment "case-1" "JPN" -1))))

(deftest treatment-history-is-append-only
  (let [t1 (r/register-treatment "case-1" "JPN" 0)
        hist (r/append [] t1)
        t2 (r/register-treatment "case-2" "JPN" 1)
        hist2 (r/append hist t2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TX-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TX-000001" (get-in hist2 [1 "record_id"])))))
