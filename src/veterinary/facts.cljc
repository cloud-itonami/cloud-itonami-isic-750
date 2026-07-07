(ns veterinary.facts
  "Per-jurisdiction veterinary-practice licensing regulatory catalog --
  the G2-style spec-basis table the Veterinary Care Governor checks
  every jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's veterinary-licensing
  requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official veterinary-
  licensing authority (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a
  real source, done -- never invent a jurisdiction's requirements to
  make coverage look bigger.

  Like `clinic.facts`'s USA entry, the USA entry here cites a single
  national aggregating reference (the American Veterinary Medical
  Association / American Association of Veterinary State Boards)
  rather than all 50 individual state veterinary boards -- an honest
  single representative citation, not a state-by-state survey, the
  same simplification every prior catalog makes when a jurisdiction's
  real regulatory structure is itself federated.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  patient-consent/diagnostic-evidence/clinician-license-verification/
  treatment-plan-documentation evidence set submitted in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "農林水産省 (Ministry of Agriculture, Forestry and Fisheries, MAFF)"
          :legal-basis "獣医師法 (Veterinarians Act)"
          :national-spec "獣医師免許制度・獣医療法"
          :provenance "https://www.maff.go.jp/"
          :required-evidence ["飼主同意記録 (owner consent record)"
                              "診断根拠引用書 (diagnostic-evidence citation)"
                              "獣医師免許確認記録 (veterinarian license verification)"
                              "治療計画書 (treatment-plan documentation)"]}
   "USA" {:name "United States"
          :owner-authority "American Association of Veterinary State Boards (AAVSB)"
          :legal-basis "State Veterinary Practice Acts (aggregated via AAVSB)"
          :national-spec "AAVSB PAVE / license-verification standards"
          :provenance "https://www.aavsb.org/"
          :required-evidence ["Owner consent record"
                              "Diagnostic-evidence citation"
                              "Veterinarian license verification"
                              "Treatment-plan documentation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Royal College of Veterinary Surgeons (RCVS)"
          :legal-basis "Veterinary Surgeons Act 1966"
          :national-spec "RCVS Code of Professional Conduct"
          :provenance "https://www.rcvs.org.uk/"
          :required-evidence ["Owner consent record"
                              "Diagnostic-evidence citation"
                              "Veterinarian license verification"
                              "Treatment-plan documentation"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundestierärztekammer (Federal Chamber of Veterinarians)"
          :legal-basis "Bundes-Tierärzteordnung (Federal Veterinarians Act)"
          :national-spec "Bundestierärztekammer Approbationsordnung"
          :provenance "https://www.bundestieraerztekammer.de/"
          :required-evidence ["Halterzustimmung (owner consent record)"
                              "Diagnosebegründung (diagnostic-evidence citation)"
                              "Approbationsnachweis (veterinarian license verification)"
                              "Behandlungsplan (treatment-plan documentation)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to administer a
  treatment on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-7500 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `veterinary.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
