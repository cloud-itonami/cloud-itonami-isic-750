(ns veterinary.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [veterinary.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Farm bovine #A-12 (owner: Sakura Ranch)" (:patient (store/case-record s "case-1"))))
      (is (= "JPN" (:jurisdiction (store/case-record s "case-1"))))
      (is (= :bovine (:species (store/case-record s "case-1"))))
      (is (true? (:food-producing? (store/case-record s "case-1"))))
      (is (= 30 (:days-until-planned-harvest (store/case-record s "case-1"))))
      (is (= 21 (:withdrawal-period-days (store/case-record s "case-1"))))
      (is (= #{:penicillin} (:contraindications (store/case-record s "case-3"))))
      (is (true? (:clinician-license-current? (store/case-record s "case-1"))))
      (is (false? (:clinician-license-current? (store/case-record s "case-4"))))
      (is (false? (:treated? (store/case-record s "case-1"))))
      (is (= ["case-1" "case-2" "case-3" "case-4" "case-5"]
             (mapv :id (store/all-cases s))))
      (is (nil? (store/credential-of s "case-1")))
      (is (nil? (store/assessment-of s "case-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/treatment-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/case-already-treated? s "case-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :case/upsert
                                 :value {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)"}})
        (is (= "Farm bovine #A-12 (owner: Sakura Ranch)" (:patient (store/case-record s "case-1"))))
        (is (= :bovine (:species (store/case-record s "case-1"))) "species preserved"))
      (testing "assessment / credential payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["case-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "case-1")))
        (store/commit-record! s {:effect :credential/set :path ["case-1"]
                                 :payload {:case-id "case-1" :verdict :current}})
        (is (= {:case-id "case-1" :verdict :current} (store/credential-of s "case-1"))))
      (testing "treatment administration drafts a treatment record and advances the sequence"
        (store/commit-record! s {:effect :case/mark-treated :path ["case-1"]})
        (is (= "JPN-TX-000000" (get (first (store/treatment-history s)) "record_id")))
        (is (= "treatment-administration-draft" (get (first (store/treatment-history s)) "kind")))
        (is (true? (:treated? (store/case-record s "case-1"))))
        (is (= 1 (count (store/treatment-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/case-already-treated? s "case-1")))
        (is (false? (store/case-already-treated? s "case-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/case-record s "nope")))
    (is (= [] (store/all-cases s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/treatment-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-cases s {"x" {:id "x" :patient "p" :species :canine
                              :proposed-treatment :amoxicillin :contraindications #{}
                              :food-producing? false :days-until-planned-harvest nil :withdrawal-period-days nil
                              :clinician-license-current? true :treated? false
                              :jurisdiction "JPN" :status :intake}})
    (is (= "p" (:patient (store/case-record s "x"))))))
