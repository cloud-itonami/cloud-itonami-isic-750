(ns veterinary.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`veterinary.operation` -> `veterinary.governor` -> `veterinary.
  store`) through a scenario adapted from this repo's own `veterinary.
  sim` demo driver (`clojure -M:dev:run`, confirmed to run correctly
  against the real seeded case directory before this file was written
  -- every id/subject it references (case-1/case-2/case-3/case-4)
  traces to `veterinary.store/demo-data`'s own seeded `:cases`, and
  every disposition it produces matches `veterinary.governor`'s own
  rules exactly, confirmed by actually running `clojure -M:dev:run`
  and reading the printed audit ledger -- unlike `cloud-itonami-
  isic-851`'s `schoolops.sim`, this repo's own sim driver was safe to
  mine directly rather than author from scratch), trimmed to a
  representative subset (one phase-3 auto-commit, three escalate-then-
  approve dispositions culminating in a real treatment administration,
  and three distinct HARD-hold reasons) and rendered deterministically
  -- no invented numbers, no timestamps in the page content, byte-
  identical across reruns against the same seed (verified by diffing
  two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [veterinary.store :as store]
            [veterinary.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :licensed-veterinarian :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every
  disposition this actor can reach: case-1 (JPN, food-producing bovine,
  30 days until planned harvest against a 21-day withdrawal period, a
  current clinician license) clears a clean `:case/intake` patch
  (phase-3 auto-commit -- `:case/intake` is the ONE op in phase 3's
  `:auto` set), then a `:jurisdiction/assess` (JPN has a real spec-
  basis in `veterinary.facts`, so it is governor-clean but still
  escalates on the phase-approval gate) and a `:credential/screen`
  (clean, also phase-approval escalate) are each approved, and finally
  a `:treatment/administer` -- the ONE real-world actuation this actor
  performs, which ALWAYS escalates (governor `high-stakes` AND
  `veterinary.phase` never adds it to any phase's `:auto` set, two
  independent layers agreeing) -- is approved and commits a real
  treatment-administration record. Then three distinct HARD-hold
  reasons, none reaching a human: a `:jurisdiction/assess` on case-2
  (whose own seeded jurisdiction is \"ATL\", absent from `veterinary.
  facts/catalog` -- no fabricated spec-basis, HARD hold); case-3's
  jurisdiction is first assessed and approved (JPN, so evidence is on
  file), then a `:treatment/administer` for case-3 HARD-holds on
  `:contraindicated` (its own seeded `:proposed-treatment` `:penicillin`
  is a member of its own seeded `:contraindications` set); and a
  `:credential/screen` on case-4 HARD-holds immediately on
  `:credential-not-current` (its own seeded `:clinician-license-
  current?` is false). Returns the resulting store -- every field read
  by `render` below is real governor/store output, not a hand-typed
  copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "case1-intake" {:op :case/intake :subject "case-1"
                                  :patch {:id "case-1" :patient "Farm bovine #A-12 (owner: Sakura Ranch)"}})

    (exec! actor "case1-assess" {:op :jurisdiction/assess :subject "case-1"})
    (approve! actor "case1-assess")

    (exec! actor "case1-screen" {:op :credential/screen :subject "case-1"})
    (approve! actor "case1-screen")

    (exec! actor "case1-treat" {:op :treatment/administer :subject "case-1"})
    (approve! actor "case1-treat")

    (exec! actor "case2-assess" {:op :jurisdiction/assess :subject "case-2"})

    (exec! actor "case3-assess" {:op :jurisdiction/assess :subject "case-3"})
    (approve! actor "case3-assess")
    (exec! actor "case3-treat" {:op :treatment/administer :subject "case-3"})

    (exec! actor "case4-screen" {:op :credential/screen :subject "case-4"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- case-row [ledger {:keys [id patient species proposed-treatment jurisdiction
                                clinician-license-current? treated?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc patient) (esc (name (or species :n-a)))
          (esc (name (or proposed-treatment :n-a)))
          (esc (or jurisdiction "n/a"))
          (if clinician-license-current? "<span class=\"ok\">current</span>" "<span class=\"err\">not current</span>")
          (if treated? "<span class=\"ok\">treated</span>" "<span class=\"muted\">not yet</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; `Ops` table, `veterinary.governor`/`veterinary.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:case/intake</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; requires an OFFICIAL spec-basis citation from <code>veterinary.facts</code> &middot; a jurisdiction with no catalog entry is a permanent HARD block, no phase or approval can ever override it</span></td></tr>"
   "        <tr><td><code>:credential/screen</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; a lapsed veterinarian license (self-reported or already on file) is an immediate, un-overridable HARD hold</span></td></tr>"
   "        <tr><td><code>:treatment/administer</code></td><td><span class=\"warn\">ALWAYS human approval, at every phase &middot; independently re-verifies contraindications, food-safety withdrawal-period sufficiency, evidence completeness, license currency and double-administration &mdash; any one of the five is a permanent HARD block</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        cases (store/all-cases db)
        case-rows (str/join "\n" (map (partial case-row ledger) cases))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-7500 &middot; veterinary activities</title><style>\n"
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
     "  <h1>Veterinary activities (ISIC 7500) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never administers a real treatment or issues a signed certificate directly</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Cases</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>veterinary.store</code> via <code>veterinary.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Case</th><th>Patient</th><th>Species</th><th>Proposed treatment</th><th>Jurisdiction</th><th>Clinician license</th><th>Treated?</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     case-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Veterinary Care Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. A jurisdiction's licensing requirements, a case's own contraindications, its food-safety withdrawal-period sufficiency and its clinician's license currency are all independently recomputed, never trusted from the advisor's rationale.</p>\n"
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
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
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
             (count (store/treatment-history db)) "treatment records )")))
