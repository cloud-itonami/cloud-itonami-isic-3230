(ns sportsgoodsmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-3230`: this
  repo previously shipped no demo page and no generator. This
  namespace drives the REAL actor stack (`sportsgoodsmfg.operation` ->
  `sportsgoodsmfg.governor` -> `sportsgoodsmfg.store`) through one
  scenario and renders the resulting SSoT + append-only ledger. No
  value on the page is typed by hand: every batch/equipment/
  maintenance/shipment/safety-concern field is read back out of
  `sportsgoodsmfg.store`, and every status/basis/hold reason is read
  out of a real ledger fact the governor wrote. The single exception
  is `action-gate-rows` below, which describes this actor's own FIXED
  op/gate contract and is commented as documentation-of-code.

  ## Input provenance

  Every entity this scenario references exists in
  `sportsgoodsmfg.store/sample-data!`:

    batches    `batch-001` (verified+registered, 500 units produced,
                            100 already shipped -> shipping headroom)
               `batch-002` (verified+registered, 80 produced,
                            75 shipped -> 5 units of headroom exactly)
               `batch-003` (UNVERIFIED + unregistered)
    equipment  `molding-001`  (verified+registered molding unit)
               `assembly-002` (UNVERIFIED + unregistered station)

  Note on the OTHER subject ids (`mnt-*`, `concern-*`, `ship-*`): for
  `:schedule-maintenance`, `:flag-safety-concern` and
  `:coordinate-shipment` the request `:subject` is the id of the DRAFT
  RECORD THE OP CREATES, not a lookup key into pre-existing state --
  `store/mem-store` starts with `:maintenance {}`, `:shipments {}` and
  `:safety-concerns []`, and the seed adds none. Minting a fresh draft
  id is how this actor is built (`sportsgoodsmfg.sim` does the same).
  The ground-truth entity each of those ops is checked against --
  `:equipment-id` / `:batch-id` inside `:value` -- is always one of
  the seeded ids above, which is exactly what the governor
  independently re-derives (`equipment-not-verified` /
  `batch-not-verified` / `shipment-quantity-exceeded`).

  ## What each subject exercises

  Clean lifecycle
    `batch-001`   `:log-production-batch` -> phase-3 AUTO-COMMIT (clean,
                  high-confidence, no physical/financial risk: the one
                  op any phase ever auto-commits)
    `batch-002`   same, second auto-commit
    `mnt-001`     `:schedule-maintenance` on the verified+registered
                  `molding-001` -> ESCALATE (never auto at ANY phase --
                  see `sportsgoodsmfg.phase`) -> human approves -> commit
    `concern-001` `:flag-safety-concern` on `molding-001` -> ALWAYS
                  escalates (`:stake :coordination/safety-concern`) ->
                  human approves -> commit
    `ship-001`    `:coordinate-shipment` 50 units of `batch-001`
                  (150/500 after commit) -> escalate -> approve -> commit
    `ship-005`    `:coordinate-shipment` 5 units of `batch-002`, filling
                  it to EXACTLY its recorded 80-unit production quantity
                  -- the boundary `registry/shipment-quantity-exceeded?`
                  deliberately allows -> escalate -> approve -> commit

  Human rejection (the third fact type the ledger can hold)
    `ship-002`    `:coordinate-shipment` 25 more units of `batch-001` ->
                  escalate -> human REJECTS -> `:approval-rejected`,
                  no SSoT mutation

  HARD holds -- one per governor rule, none of which reaches a human
    `mnt-002`     `:equipment-not-verified` (targets `assembly-002`)
    `mnt-001`     `:already-scheduled` (same window, twice)
    `mnt-003`     `:actuate-equipment-blocked` (PERMANENT, no override)
    `ship-003`    `:batch-not-verified` (targets `batch-003`)
    `ship-004`    `:shipment-quantity-exceeded` (75 shipped + 10 > 80)
    `batch-003`   `:invalid-product-type`, `:invalid-impact-rating`,
                  `:invalid-weight`, `:invalid-defect-rate`,
                  `:safety-certification-authority-blocked` (PERMANENT),
                  `:not-propose-effect`, and `:unknown-op` +
                  `:equipment-control-blocked` together

  ## Determinism

  Two consecutive runs are byte-identical: the store is a fresh
  `mem-store` each run, `all-batches`/`all-equipment`/`all-maintenance`
  are sorted by id, the ledger and the safety-concern log are
  append-ordered vectors, draft record numbers come from a per-run
  sequence counter (`MNT-000000`, `SHP-000000`, ...), and nothing on
  the page carries a timestamp or a random id.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sportsgoodsmfg.store :as store]
            [sportsgoodsmfg.operation :as op]
            [langgraph.graph :as g]))

;; The human on the other side of every escalation. Same shape
;; `sportsgoodsmfg.sim` uses; phase 3 is `sportsgoodsmfg.phase`'s
;; `default-phase` (supervised-auto).
(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through the scenario documented in the ns
  docstring and returns the resulting store. Everything `render` reads
  below is this function's real output -- real governor verdicts, real
  ledger facts, real SSoT mutations."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; --- clean lifecycle -------------------------------------------------
    ;; The only op any phase ever auto-commits: clean, high-confidence,
    ;; administrative record logging. No human touched these two.
    (exec! actor "t01" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:product-type :ball :last-assessed "2026-07-14"}})
    (exec! actor "t02" {:op :log-production-batch :effect :propose :subject "batch-002"
                        :patch {:product-type :protective-gear :last-assessed "2026-07-14"}})

    ;; Never auto at any phase -- a maintenance window means real
    ;; production downtime on real equipment.
    (exec! actor "t03" {:op :schedule-maintenance :effect :propose :subject "mnt-001"
                        :value {:equipment-id "molding-001"
                                :maintenance-type :mold-cavity-inspection
                                :scheduled-date "2026-08-01"
                                :actuate-equipment? false}})
    (approve! actor "t03")

    ;; Always escalates on `:stake`, regardless of confidence.
    (exec! actor "t04" {:op :flag-safety-concern :effect :propose :subject "concern-001"
                        :value {:equipment-id "molding-001" :severity :moderate
                                :description "impact-protection rating below threshold on last QC sample"}})
    (approve! actor "t04")

    (exec! actor "t05" {:op :coordinate-shipment :effect :propose :subject "ship-001"
                        :value {:batch-id "batch-001" :units 50.0
                                :destination "buyer-retailer-north"}})
    (approve! actor "t05")

    ;; --- a human says no -------------------------------------------------
    (exec! actor "t06" {:op :coordinate-shipment :effect :propose :subject "ship-002"
                        :value {:batch-id "batch-001" :units 25.0
                                :destination "buyer-retailer-west"}})
    (reject! actor "t06")

    ;; --- HARD holds: each one never reaches a human ----------------------
    (exec! actor "t07" {:op :schedule-maintenance :effect :propose :subject "mnt-002"
                        :value {:equipment-id "assembly-002"
                                :maintenance-type :fastener-torque-check
                                :scheduled-date "2026-08-01"
                                :actuate-equipment? false}})

    (exec! actor "t08" {:op :schedule-maintenance :effect :propose :subject "mnt-001"
                        :value {:equipment-id "molding-001"
                                :maintenance-type :mold-cavity-inspection
                                :scheduled-date "2026-08-01"
                                :actuate-equipment? false}})

    (exec! actor "t09" {:op :schedule-maintenance :effect :propose :subject "mnt-003"
                        :value {:equipment-id "molding-001" :maintenance-type :force-run
                                :scheduled-date "2026-09-01"
                                :actuate-equipment? true}})

    (exec! actor "t10" {:op :coordinate-shipment :effect :propose :subject "ship-003"
                        :value {:batch-id "batch-003" :units 20.0
                                :destination "buyer-retailer-south"}})

    (exec! actor "t11" {:op :coordinate-shipment :effect :propose :subject "ship-004"
                        :value {:batch-id "batch-002" :units 10.0
                                :destination "buyer-retailer-east"}})

    (exec! actor "t12" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:product-type :unobtainium-gear}})
    (exec! actor "t13" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:impact-rating-percent 250}})
    (exec! actor "t14" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:weight-grams -5.0}})
    (exec! actor "t15" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:defect-rate-percent 999.0}})
    (exec! actor "t16" {:op :log-production-batch :effect :propose :subject "batch-003"
                        :patch {:issue-safety-certification? true}})
    ;; A mis-wired caller trying to bypass proposal-only mode.
    (exec! actor "t17" {:op :log-production-batch :effect :direct-write :subject "batch-003"
                        :patch {:product-type :ball}})
    ;; An op outside the closed allowlist.
    (exec! actor "t18" {:op :actuate-molding-unit :effect :propose :subject "batch-003"})

    ;; --- the boundary the recompute deliberately allows -------------------
    ;; 75 already shipped + 5 = EXACTLY the 80 units batch-002 recorded
    ;; as produced. Legal; 10 units (t11 above) was not.
    (exec! actor "t19" {:op :coordinate-shipment :effect :propose :subject "ship-005"
                        :value {:batch-id "batch-002" :units 5.0
                                :destination "buyer-retailer-central"}})
    (approve! actor "t19")

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- dash
  "Renders a missing field as an em-dash rather than the string \"nil\"
  -- absence is a real state in this store (`assembly-002` genuinely
  has no `:last-maintenance-date`)."
  [v]
  (if (or (nil? v) (and (string? v) (str/blank? v))) "&mdash;" (esc v)))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))

(defn- num-cell [v]
  (if (nil? v) "&mdash;" (str "<span class=\"num\">" (esc v) "</span>")))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell
  "The subject's last ledger fact, as a status.

  Branches ONLY on fact types `sportsgoodsmfg.store/append-ledger!` is
  actually called with. Reading `sportsgoodsmfg.operation`, that is
  exactly three: `:committed` (the `:commit` node) and
  `:governor-hold` / `:approval-rejected` (the `:hold` node).
  `:approval-granted` and `:approval-requested` exist, but they are
  written to the graph's in-memory `:audit` channel and NEVER reach
  the ledger -- branching on them here would be dead code."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed        "<span class=\"ok\">committed</span>"
      :governor-hold    (str "<span class=\"critical\">HARD hold &middot; "
                             (esc (kw-name (or (first (:basis f)) :unknown)))
                             "</span>")
      :approval-rejected "<span class=\"warn\">approval rejected by human</span>"
      nil               "<span class=\"muted\">no activity</span>"
      (str "<span class=\"muted\">" (esc (kw-name (:t f))) "</span>"))))

(defn- registry-cell
  "The two independent ground-truth facts the governor re-derives for
  itself (`registry/equipment-ready?` / `registry/batch-ready?`)."
  [{:keys [verified? registered?]}]
  (cond
    (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
    verified?                   "<span class=\"warn\">verified, not registered</span>"
    registered?                 "<span class=\"warn\">registered, not verified</span>"
    :else                       "<span class=\"critical\">unverified &amp; unregistered</span>"))

(defn- batch-row [ledger {:keys [id product-type lot-number impact-rating-percent
                                 quantity-units shipped-units defect-rate-percent] :as b}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (dash (kw-name product-type)) (dash lot-number)
          (num-cell impact-rating-percent)
          (num-cell quantity-units) (num-cell shipped-units)
          (num-cell defect-rate-percent)
          (registry-cell b)
          (status-cell ledger id)))

(defn- equipment-row [windows {:keys [id kind last-maintenance-date
                                      last-scheduled-maintenance-date] :as e}]
  (format (str "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td>"
               "<td>%s</td><td>%s</td><td>%s</td></tr>")
          (esc id) (dash (kw-name kind)) (registry-cell e)
          (dash last-maintenance-date) (dash last-scheduled-maintenance-date)
          (num-cell (count (filter #(= id (:equipment-id %)) windows)))))

(defn- maintenance-row [ledger {:keys [id equipment-id maintenance-type scheduled-date
                                       maintenance-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td><code>%s</code></td><td>%s</td></tr>")
          (esc id) (dash equipment-id) (dash (kw-name maintenance-type))
          (dash scheduled-date) (dash maintenance-number)
          (status-cell ledger id)))

(defn- shipment-row [ledger {:keys [id batch-id units destination shipment-number]}]
  (format (str "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td>"
               "<td>%s</td><td><code>%s</code></td><td>%s</td></tr>")
          (esc id) (dash batch-id) (num-cell units) (dash destination)
          (dash shipment-number)
          (status-cell ledger id)))

(defn- concern-row [ledger {:keys [id equipment-id severity description]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (dash equipment-id) (dash (kw-name severity)) (dash description)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject basis violations summary]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-name t)) (esc (kw-name (or op :n-a))) (esc subject)
          (if (seq basis) (esc (str/join ", " (map kw-name basis))) "&mdash;")
          (if (seq violations)
            (esc (str/join " / " (map :detail violations)))
            (dash summary))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own CLOSED four-op contract
  ;; (README `What this actor does`, `sportsgoodsmfg.governor`'s
  ;; allowlists, `sportsgoodsmfg.phase`'s per-phase `:auto` sets).
  ;; This is documentation of fixed code, not runtime telemetry, so it
  ;; is legitimately hand-described rather than derived from the run
  ;; above -- everything else on this page is not.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the only auto-eligible op in any phase</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never in any phase's <code>:auto</code> set &middot; equipment verified/registered re-derived independently</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; <code>:stake :coordination/safety-concern</code> escalates regardless of confidence</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; shipped-vs-produced quantity recomputed from the batch's own record, never from the proposal's claim</span></td></tr>"])

(def ^:private permanent-block-rows
  ;; Same category: a fixed description of the two PERMANENT blocks
  ;; `sportsgoodsmfg.governor` enforces unconditionally. Both are
  ;; exercised for real by `run-demo!` (see `mnt-003` and `batch-003`
  ;; in the ledger below).
  ["        <tr><td><code>:actuate-equipment-blocked</code></td><td>direct molding/assembly-line-equipment actuation &mdash; draft scheduling only, no phase and no human approval can override</td></tr>"
   "        <tr><td><code>:safety-certification-authority-blocked</code></td><td>self-issuing a product-safety/impact-protection compliance certification &mdash; exclusive authority of the accredited testing/certification body</td></tr>"])

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n")
           "        <tr><td colspan=\"9\" class=\"muted\">no records</td></tr>\n")
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

(defn render
  "Renders the operator console from a store `db` that has already run
  `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        windows (store/all-maintenance db)
        concerns (vec (store/safety-concerns db))
        ;; Shipment entities have no `all-*` accessor on the Store
        ;; protocol, so the ids come from the ledger's own committed
        ;; `:coordinate-shipment` facts and each row is then read back
        ;; out of the store -- still entirely real, never invented.
        shipments (->> ledger
                       (filter #(and (= :committed (:t %))
                                     (= :coordinate-shipment (:op %))))
                       (map :subject)
                       distinct
                       (keep #(store/shipment db %)))
        commits (count (filter #(= :committed (:t %)) ledger))
        holds (count (filter #(= :governor-hold (:t %)) ledger))
        rejected (count (filter #(= :approval-rejected (:t %)) ledger))]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\">\n"
     "<head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<meta name=\"color-scheme\" content=\"light\">"
     "<title>cloud-itonami-isic-3230 &middot; manufacture of sports goods</title>"
     "<style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head>\n<body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of sports goods (ISIC 3230) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"badge\">read-only sample &middot; governor-gated &middot; maintenance scheduling, safety-concern flagging and shipment coordination are always human-approved</p>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>sportsgoodsmfg.store</code> by <code>sportsgoodsmfg.render-html</code> (<code>clojure -M:dev:render-html</code>), by actually running <code>sportsgoodsmfg.operation</code> &rarr; <code>sportsgoodsmfg.governor</code> &rarr; <code>sportsgoodsmfg.store</code>. Nothing below is hand-written: every status, hold reason and record field is read back out of the store and the append-only ledger this run produced.</p>\n"
     "    <p><span class=\"ok\">" commits " committed</span> &middot; "
     "<span class=\"critical\">" holds " HARD hold" (when (not= 1 holds) "s") "</span> &middot; "
     "<span class=\"warn\">" rejected " rejected by a human</span> &middot; "
     "<span class=\"muted\">" (count ledger) " ledger facts total</span></p>\n"
     "  </section>\n"

     (section "Production batches"
              "The plant's own batch records. <code>Produced</code> and <code>Shipped</code> are the batch's own permanent fields &mdash; the governor recomputes shipment headroom from these two, never from a shipment proposal's self-reported quantity."
              ["Batch" "Product type" "Lot" "Impact rating %" "Produced (units)"
               "Shipped (units)" "Defect rate %" "QC / registry" "Last op status"]
              (map (partial batch-row ledger) batches))

     (section "Molding / assembly / finishing equipment"
              "Equipment records. Maintenance may only ever be scheduled against a unit that is independently <em>verified</em> AND <em>registered</em>; <code>assembly-002</code> is neither, so every window aimed at it is HARD-held."
              ["Equipment" "Kind" "Registry status" "Last maintenance"
               "Last scheduled window" "Draft windows on file"]
              (map (partial equipment-row windows) equipment))

     (section "Maintenance windows (drafts)"
              "Draft maintenance windows committed to the SSoT. These are RECORDS a plant coordinator keeps &mdash; this actor never actuates the equipment itself."
              ["Window" "Equipment" "Type" "Scheduled date" "Draft record no." "Status"]
              (map (partial maintenance-row ledger) windows))

     (section "Outbound shipments (drafts)"
              "Draft shipment coordination records. No freight carrier is dispatched; <code>ship-005</code> fills <code>batch-002</code> to exactly its recorded production quantity, which the recompute deliberately allows."
              ["Shipment" "Batch" "Units" "Destination" "Draft record no." "Status"]
              (map (partial shipment-row ledger) shipments))

     (section "Safety concerns"
              "The append-only safety-concern log. A concern may be raised against any equipment, verified or not &mdash; safety reporting is never blocked on an administrative technicality &mdash; but it always requires human sign-off before it is recorded."
              ["Concern" "Equipment" "Severity" "Description" "Status"]
              (map (partial concern-row ledger) concerns))

     (section "Action gate (Sports Goods Plant Operations Governor)"
              "The actor's closed four-op contract. HARD holds cannot be overridden by any phase or any human."
              ["Op" "Gate"]
              action-gate-rows)

     (section "Permanent blocks"
              "Two scope boundaries this actor can never cross, at any phase, with any approval."
              ["Rule" "What is blocked"]
              permanent-block-rows)

     (section "Audit ledger (this run)"
              "The append-only decision-fact log. Only three fact types are ever written here &mdash; <code>committed</code>, <code>governor-hold</code> and <code>approval-rejected</code>; approval requests and grants live in the run's in-memory audit channel and never become durable facts."
              ["Fact" "Op" "Subject" "Basis" "Detail / summary"]
              (map ledger-row ledger))

     "</main>\n"
     "<footer><p class=\"muted\">Regenerate with <code>clojure -M:dev:render-html</code>. Deterministic: two consecutive runs are byte-identical.</p></footer>\n"
     "</body>\n</html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (io/make-parents out)
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-maintenance db)) "maintenance windows,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns )")))
