(ns vetops.store-contract-test
  "Contract tests for `vetops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [vetops.store :as store]))

(deftest mem-store-resource-lookup
  (testing "MemStore can store and retrieve resources by ID (string keys)"
    (let [resources {"r1" {:resource-id "r1" :location "Exam Room A" :registered? true :verified? true}}
          s (store/mem-store resources)]
      (is (some? (store/resource s "r1")))
      (is (nil? (store/resource s "r99"))))))

(deftest mem-store-all-resources
  (testing "MemStore returns all resources in sorted order"
    (let [resources {"r2" {:resource-id "r2" :location "Kennel B"}
                     "r1" {:resource-id "r1" :location "Exam Room A"}
                     "r3" {:resource-id "r3" :location "Procedure Bay C"}}
          s (store/mem-store resources)
          all-r (store/all-resources s)]
      (is (= 3 (count all-r)))
      (is (= "r1" (:resource-id (first all-r))))
      (is (= "r3" (:resource-id (last all-r)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :coordinate-appointment-scheduling :resource-id "r1" :value {:status "available"}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-resources
  (testing "MemStore with-resources replaces the resource directory"
    (let [s (store/mem-store {})
          new-resources {"r1" {:resource-id "r1" :location "Exam Room A"}}]
      (is (= 0 (count (store/all-resources s))))
      (store/with-resources s new-resources)
      (is (= 1 (count (store/all-resources s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo resources"
    (let [s (store/seed-db)]
      (is (> (count (store/all-resources s)) 0))
      (is (some? (store/resource s "exam-1")))
      (is (some? (store/resource s "kennel-12")))
      (is (some? (store/resource s "exam-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for resource-id"
    (let [demo (store/demo-data)
          resources (:resources demo)]
      (doseq [[k v] resources]
        (is (string? k) "keys must be strings")
        (is (string? (:resource-id v)) "resource-id must be string")
        (is (= k (:resource-id v)) "key must match resource-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
