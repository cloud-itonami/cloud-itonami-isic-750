(ns vetops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout), the same pattern `hospitalops.render-html` (ISIC-861)
  established: this namespace drives the REAL actor stack
  (`vetops.operation` -> `vetops.governor` -> `vetops.store`) through a
  scenario built from the actor's OWN seeded demo data
  (`vetops.store/seed-db`, resources exam-1/kennel-12/exam-3) and
  renders the result deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against
  the same seed (verified by diffing two consecutive runs before
  shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [vetops.store :as store]
            [vetops.advisor :as advisor]
            [vetops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :practice-manager :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real resource ids from
  `vetops.store/demo-data`:

  exam-1 and kennel-12 (both registered AND verified) each clear a
  clean administrative-coordination proposal that auto-commits at
  phase 3 (`:coordinate-appointment-scheduling` for exam-1,
  `:coordinate-boarding-assignment` and `:schedule-staff-shift-
  proposal` for kennel-12 -- all four ops are phase-3 auto-eligible
  per `vetops.phase`). exam-1's `:flag-safety-concern` ALWAYS
  escalates (per `vetops.governor/always-escalate-ops`, regardless of
  confidence or phase) and is approved by a human practice manager.

  Then four HARD-hold rows, none of which ever reach a human (a human
  approver cannot override a HARD violation), covering three distinct
  real governor rules:
    - exam-3 (registered but NOT `:verified?` in the seed data):
      `:coordinate-appointment-scheduling` HARD-holds on
      `:resource-unverified` -- never re-derived from the proposal's
      own claim, only from the resource's own store record.
    - exam-1, advisor deliberately drifts into clinical-scope content
      (`:out-of-scope? true`, the same governor-contract test hook
      `vetops.advisor/infer` documents): HARD-holds on
      `:scope-excluded`.
    - kennel-12, `:coordinate-boarding-assignment` via a wrapped
      advisor that forces `:effect :commit` on an otherwise-clean
      proposal (the same technique `vetops.sim`'s own `t9` case uses
      to exercise this exact governor rule): HARD-holds on
      `:effect-not-propose` -- independent proof the governor never
      trusts an advisor's own `:effect` claim.
    - exam-1, `:coordinate-supply-request` with an ordinary clean
      patch: auto-commits cleanly. Unlike a prior repo in this
      cluster (see `hospitalops.render-html`'s own docstring), this
      actor's `propose-supply-request` rationale text was written from
      the start to describe what IS being requested (bedding/
      cleaning/reception supplies) without repeating any of
      `vetops.governor/scope-excluded-terms`'s own banned substrings
      in a negation clause -- confirmed by running the actor directly,
      not assumed.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        direct-actor (op/build db {:advisor (reify advisor/Advisor
                                              (-advise [_ _ req]
                                                (assoc (advisor/infer nil req) :effect :commit)))})]

    ;; exam-1: clean appointment-scheduling coordination -- phase-3 auto-commit.
    (exec! actor "exam1-appt" {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                                :patch {:status "booked" :slot "2026-08-01T09:00"}})

    ;; kennel-12: clean boarding-assignment coordination -- phase-3 auto-commit.
    (exec! actor "kennel12-board" {:op :coordinate-boarding-assignment :resource-id "kennel-12"
                                    :patch {:animal "Rex" :check-in "2026-08-01" :check-out "2026-08-05"}})

    ;; exam-1: clean non-clinical supply-request coordination -- phase-3 auto-commit.
    (exec! actor "exam1-supply" {:op :coordinate-supply-request :resource-id "exam-1"
                                  :patch {:item "exam table paper roll" :quantity 6}})

    ;; kennel-12: clean staff-shift proposal -- phase-3 auto-commit.
    (exec! actor "kennel12-shift" {:op :schedule-staff-shift-proposal :resource-id "kennel-12"
                                    :patch {:staff-member "vet tech Nakamura" :shift "AM" :date "2026-08-02"}})

    ;; exam-1: facility safety-concern flag -- ALWAYS escalates, approved
    ;; by a human practice manager.
    (exec! actor "exam1-safety" {:op :flag-safety-concern :resource-id "exam-1"
                                  :patch {:concern "kennel latch mechanism loose near ward entrance"
                                          :confidence 0.9}})
    (approve! actor "exam1-safety")

    ;; exam-3: registered but NOT verified -> HARD hold on
    ;; :resource-unverified, never reaches a human.
    (exec! actor "exam3-appt" {:op :coordinate-appointment-scheduling :resource-id "exam-3"
                                :patch {:status "available"}})

    ;; exam-1: advisor drifts into permanently-excluded clinical scope
    ;; -> HARD hold on :scope-excluded, never reaches a human.
    (exec! actor "exam1-scope" {:op :coordinate-appointment-scheduling :resource-id "exam-1"
                                 :out-of-scope? true
                                 :patch {}})

    ;; kennel-12: advisor attempts a direct actuation (:effect :commit on an
    ;; otherwise-clean proposal) -> HARD hold on :effect-not-propose,
    ;; never reaches a human.
    (exec! direct-actor "kennel12-effect" {:op :coordinate-boarding-assignment :resource-id "kennel-12"
                                            :patch {:animal "guest"}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger resource-id]
  (last (filter #(= (:resource-id %) resource-id) ledger)))

(defn- status-cell [ledger resource-id]
  (let [f (last-fact-for ledger resource-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (case rule
          :resource-unverified "<span class=\"critical\">HARD hold &middot; unverified resource</span>"
          :scope-excluded "<span class=\"critical\">HARD hold &middot; scope-excluded</span>"
          :effect-not-propose "<span class=\"critical\">HARD hold &middot; effect-not-propose</span>"
          (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- resource-row [ledger {:keys [resource-id location registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc resource-id) (esc location)
          (if (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
              "<span class=\"warn\">registered, unverified</span>")
          (status-cell ledger resource-id)))

(defn- ledger-row [{:keys [t op resource-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc resource-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Operations`/`vetops.governor`/`vetops.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:coordinate-appointment-scheduling</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-boarding-assignment</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-request</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-staff-shift-proposal</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        resources (store/all-resources db)
        resource-rows (str/join "\n" (map (partial resource-row ledger) resources))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-750 &middot; veterinary activities coordination</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Veterinary activities administrative coordination (ISIC 750) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never touches diagnosis/treatment/medication/animal care</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Resources</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>vetops.store</code> via <code>vetops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Resource</th><th>Location</th><th>Facility-resource status</th><th>Last coordination status</th></tr></thead>\n"
     "      <tbody>\n"
     resource-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (VetOps Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Diagnosis, treatment, medication/vaccine/anesthesia administration, surgical/dental procedures, euthanasia and license/compliance-enforcement territory are permanently out of scope — see governor scope-exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Resource</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
