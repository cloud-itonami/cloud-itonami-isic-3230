(ns sportsgoodsmfg.render-html
  "Build-time HTML renderer for the sports-goods plant-operations
  operator console (`docs/samples/operator-console.html`).

  This is NOT a mock-up. It drives the REAL actor stack --
  `sportsgoodsmfg.store`'s seeded MemStore, the real
  `sportsgoodsmfg.operation` langgraph StateGraph (advisor -> governor
  -> phase gate -> commit | hold | human approval), and the real
  `sportsgoodsmfg.governor` -- and then renders whatever the actor
  actually left behind in the SSoT and the append-only ledger. Every
  batch id, equipment id, ledger fact, draft record number and HARD-hold
  rule name on the page was produced by that run; nothing on the page is
  hand-written domain data.

  Two consequences worth stating, because they constrain what may be
  rendered:

    - Every subject driven below is either seeded by
      `store/sample-data!` (`batch-001`..`batch-003`, `molding-001`,
      `assembly-002`) or is the id of the record the very op being run
      registers (`mnt-*`, `ship-*`, `concern-1`). No fabricated entity
      is ever handed to the actor.

    - `sportsgoodsmfg.operation` appends only three fact types to the
      store ledger -- `:committed` (from the `:commit` node) and
      `:governor-hold` / `:approval-rejected` (from the `:hold` node).
      `:approval-requested` and `:approval-granted` exist ONLY on the
      in-memory `:audit` channel and are never persisted, so the status
      renderer below deliberately does not branch on them: an
      unreachable status branch is a lie about the ledger.

  Deterministic: the advisor is the deterministic mock, the store is an
  atom, and every collection rendered is either sorted by id or is an
  append-only vector -- so re-running produces byte-identical output.

  Run: `clojure -M:dev:render-html [out-file]`"
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [sportsgoodsmfg.governor :as governor]
            [sportsgoodsmfg.operation :as op]
            [sportsgoodsmfg.phase :as phase]
            [sportsgoodsmfg.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn- step!
  "Run one request through the actor. When `approve?` and the run
  actually paused on `interrupt-before #{:request-approval}`, resume it
  with a human approval. Records what the actor did (never what we
  expected it to do)."
  [runs actor tid request approve?]
  (let [r1 (exec! actor tid request)
        paused? (= :interrupted (:status r1))
        r2 (if (and approve? paused?) (approve! actor tid) r1)]
    (swap! runs conj
           {:tid tid
            :op (:op request)
            :subject (:subject request)
            :paused? paused?
            :approved? (boolean (and approve? paused?))
            :disposition (get-in r2 [:state :disposition])})
    r2))

(defn run-demo!
  "Seeds a MemStore, builds the real OperationActor and walks it through
  a clean shift plus every HARD-hold path this governor can produce
  against the seeded plant. Returns {:db .. :runs ..}."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)
        runs (atom [])
        run! (fn [tid request & [approve?]] (step! runs actor tid request (boolean approve?)))]

    ;; --- clean shift ---------------------------------------------------
    ;; phase 3 auto-commits governor-clean :log-production-batch only.
    (run! "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                :patch {:product-type :ball :last-assessed "2026-07-14"}})
    (run! "t2" {:op :log-production-batch :effect :propose :subject "batch-003"
                :patch {:defect-rate-percent 1.1 :last-assessed "2026-07-20"}})
    ;; :schedule-maintenance is never auto-eligible at any phase -> always a human.
    (run! "t3" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                :value {:equipment-id "molding-001" :maintenance-type :mold-cavity-inspection
                        :scheduled-date "2026-08-01" :actuate-equipment? false}}
          :approve)
    ;; a safety concern is always high-stakes, and deliberately NOT gated on
    ;; the referenced equipment being verified.
    (run! "t4" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                :value {:equipment-id "assembly-002" :severity :moderate
                        :description "impact-protection rating below threshold on last QC sample"}}
          :approve)
    (run! "t5" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                :value {:batch-id "batch-001" :units 50.0
                        :destination "buyer-retailer-north"}}
          :approve)

    ;; --- HARD holds, each exercised directly ---------------------------
    (run! "t6" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                :value {:equipment-id "assembly-002" :maintenance-type :fastener-torque-check
                        :scheduled-date "2026-08-05" :actuate-equipment? false}})
    (run! "t7" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                :value {:batch-id "batch-003" :units 20.0
                        :destination "buyer-retailer-south"}})
    (run! "t8" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                :value {:batch-id "batch-002" :units 10.0
                        :destination "buyer-retailer-east"}})
    (run! "t9" {:op :coordinate-shipment :effect :propose :subject "ship-4"
                :value {:batch-id "batch-001"
                        :destination "buyer-retailer-west"}})
    (run! "t10" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                 :value {:equipment-id "molding-001" :maintenance-type :force-run
                         :scheduled-date "2026-09-01" :actuate-equipment? true}})
    (run! "t11" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                 :value {:equipment-id "molding-001" :maintenance-type :mold-cavity-inspection
                         :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (run! "t12" {:op :log-production-batch :effect :propose :subject "batch-003"
                 :patch {:issue-safety-certification? true}})
    (run! "t13" {:op :log-production-batch :effect :propose :subject "batch-001"
                 :patch {:impact-rating-percent 250}})
    (run! "t14" {:op :log-production-batch :effect :propose :subject "batch-002"
                 :patch {:product-type :unobtainium-gear}})
    (run! "t15" {:op :log-production-batch :effect :direct-write :subject "batch-001"
                 :patch {:product-type :ball}})
    (run! "t16" {:op :actuate-molding-unit :effect :propose :subject "batch-001"})

    {:db db :runs @runs}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [v] (if (keyword? v) (name v) (str v)))

(defn- cell [v]
  (cond (nil? v) "-"
        (true? v) "yes"
        (false? v) "no"
        (keyword? v) (name v)
        :else (str v)))

(defn- flag [v] (if (true? v) "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- join-names [xs] (str/join ", " (map nm xs)))

(defn- last-fact-for [ledger id]
  (last (filter #(= (:subject %) id) ledger)))

(defn- status-cell
  "Only the three fact types `sportsgoodsmfg.operation` actually appends
  to the store ledger are branched on here."
  [ledger id]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f))
      (str "<span class=\"ok\">committed</span> <span class=\"muted\">" (esc (nm (:op f))) "</span>")
      (= :approval-rejected (:t f))
      (str "<span class=\"critical\">approval rejected</span> <span class=\"muted\">"
           (esc (nm (:op f))) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold: " (esc (join-names (:basis f))) "</span>")
      :else "<span class=\"muted\">-</span>")))

(defn- tr [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title note body]
  (str "<section class=\"card\"><h2>" title "</h2>"
       (if note (str "<p class=\"muted\">" note "</p>") "")
       body "</section>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead><tbody>\n"
       (if (seq rows) (str/join "\n" rows)
           (str "        <tr><td colspan=\"" (count headers)
                "\" class=\"muted\">none</td></tr>"))
       "\n      </tbody></table>"))

;; --- individual sections, all derived from real state ---------------

(defn- batches-section [db ledger]
  (section "Production batches"
           (str "Seeded by <code>sportsgoodsmfg.store/sample-data!</code>, mutated only by the actor's "
                "<code>:commit</code> node. <code>verified?</code>/<code>registered?</code> are the "
                "ground truth the governor re-derives independently.")
           (table ["Batch" "Product type" "Lot" "Impact %" "Quantity (units)" "Shipped (units)"
                   "Weight (g)" "Defect %" "Verified?" "Registered?" "Last assessed" "Ledger"]
                  (for [b (store/all-batches db)]
                    (tr (str "<code>" (esc (:id b)) "</code>")
                        (esc (cell (:product-type b)))
                        (esc (cell (:lot-number b)))
                        (esc (cell (:impact-rating-percent b)))
                        (esc (cell (:quantity-units b)))
                        (esc (cell (:shipped-units b)))
                        (esc (cell (:weight-grams b)))
                        (esc (cell (:defect-rate-percent b)))
                        (flag (:verified? b))
                        (flag (:registered? b))
                        (esc (cell (:last-assessed b)))
                        (status-cell ledger (:id b)))))))

(defn- equipment-section [db ledger]
  (section "Molding / assembly / finishing equipment"
           (str "Maintenance may only ever be scheduled against equipment that is both "
                "<code>verified?</code> and <code>registered?</code> "
                "(<code>registry/equipment-ready?</code>).")
           (table ["Equipment" "Kind" "Verified?" "Registered?" "Last maintenance"
                   "Last scheduled window" "Ledger"]
                  (for [e (store/all-equipment db)]
                    (tr (str "<code>" (esc (:id e)) "</code>")
                        (esc (cell (:kind e)))
                        (flag (:verified? e))
                        (flag (:registered? e))
                        (esc (cell (:last-maintenance-date e)))
                        (esc (cell (:last-scheduled-maintenance-date e)))
                        (status-cell ledger (:id e)))))))

(defn- maintenance-section [db]
  (section "Maintenance-window drafts"
           (str "Committed drafts only. Record numbers come from "
                "<code>sportsgoodsmfg.registry/register-maintenance</code>; nothing here actuates "
                "equipment.")
           (table ["Maintenance" "Record no." "Equipment" "Type" "Scheduled date" "Scheduled?" "Actuate?"]
                  (for [m (store/all-maintenance db)]
                    (tr (str "<code>" (esc (:id m)) "</code>")
                        (str "<code>" (esc (cell (:maintenance-number m))) "</code>")
                        (str "<code>" (esc (cell (:equipment-id m))) "</code>")
                        (esc (cell (:maintenance-type m)))
                        (esc (cell (:scheduled-date m)))
                        (flag (:scheduled? m))
                        (esc (cell (:actuate-equipment? m))))))))

(defn- shipments-section [db]
  (let [ids (map #(get % "shipment_id") (store/shipment-history db))]
    (section "Shipment-coordination drafts"
             (str "Committed drafts only. Record numbers come from "
                  "<code>sportsgoodsmfg.registry/register-shipment</code>; no freight carrier is "
                  "dispatched.")
             (table ["Shipment" "Record no." "Batch" "Units" "Destination"]
                    (for [s (keep #(store/shipment db %) ids)]
                      (tr (str "<code>" (esc (:id s)) "</code>")
                          (str "<code>" (esc (cell (:shipment-number s))) "</code>")
                          (str "<code>" (esc (cell (:batch-id s))) "</code>")
                          (esc (cell (:units s)))
                          (esc (cell (:destination s)))))))))

(defn- concerns-section [db]
  (section "Safety concerns"
           (str "<code>:flag-safety-concern</code> is always <code>:coordination/safety-concern</code> "
                "stake &mdash; it always reaches a human, and is deliberately never blocked on the "
                "referenced equipment being verified.")
           (table ["Concern" "Equipment" "Severity" "Description"]
                  (for [c (store/safety-concerns db)]
                    (tr (str "<code>" (esc (:id c)) "</code>")
                        (str "<code>" (esc (cell (:equipment-id c))) "</code>")
                        (esc (cell (:severity c)))
                        (esc (cell (:description c))))))))

(defn- gate-section []
  (section "Action gate"
           (str "Read directly out of <code>sportsgoodsmfg.phase/phases</code> and "
                "<code>sportsgoodsmfg.governor</code> at render time &mdash; not transcribed. "
                "Active phase: <code>" phase/default-phase "</code>, confidence floor "
                "<code>" governor/confidence-floor "</code>.")
           (str
            (table ["Phase" "Label" "May write" "May auto-commit when governor-clean"]
                   (for [p (sort (keys phase/phases))]
                     (let [{:keys [label writes auto]} (get phase/phases p)]
                       (tr (str "<code>" p "</code>"
                                (when (= p phase/default-phase) " <span class=\"ok\">active</span>"))
                           (esc label)
                           (if (seq writes) (str "<code>" (esc (join-names (sort writes))) "</code>")
                               "<span class=\"muted\">none</span>")
                           (if (seq auto) (str "<code>" (esc (join-names (sort auto))) "</code>")
                               "<span class=\"muted\">none &mdash; every write needs a human</span>")))))
            "<table><tbody>\n"
            (str/join
             "\n"
             [(tr "Ops on the closed allowlist"
                  (str "<code>" (esc (join-names (sort governor/allowed-ops))) "</code>"))
              (tr "Proposal effects on the closed allowlist"
                  (str "<code>" (esc (join-names (sort-by nm governor/allowed-proposal-effects))) "</code>"))
              (tr "Always-human stakes"
                  (str "<code>" (esc (join-names (sort-by nm governor/high-stakes))) "</code>"))])
            "\n      </tbody></table>")))

(defn- holds-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (section "Governor HARD holds"
             (str "Produced by <code>sportsgoodsmfg.governor/check</code> on deliberately "
                  "non-compliant input during this run. Rule names and details are the governor's "
                  "own, verbatim.")
             (table ["Op" "Subject" "Rule" "Detail" "Confidence"]
                    (for [h holds
                          v (:violations h)]
                      (tr (str "<code>" (esc (nm (:op h))) "</code>")
                          (str "<code>" (esc (cell (:subject h))) "</code>")
                          (str "<span class=\"critical\">" (esc (nm (:rule v))) "</span>")
                          (esc (:detail v))
                          (esc (cell (:confidence h)))))))))

(defn- runs-section [runs]
  (section "Actor runs"
           (str "One graph run = one coordination request. "
                "<code>paused</code> means <code>interrupt-before #{:request-approval}</code> "
                "actually halted the graph and handed the decision to a human.")
           (table ["Thread" "Op" "Subject" "Paused for approval?" "Human approved?" "Final disposition"]
                  (for [r runs]
                    (tr (str "<code>" (esc (:tid r)) "</code>")
                        (str "<code>" (esc (nm (:op r))) "</code>")
                        (str "<code>" (esc (cell (:subject r))) "</code>")
                        (if (:paused? r) "<span class=\"warn\">paused</span>"
                            "<span class=\"muted\">no</span>")
                        (if (:approved? r) "<span class=\"ok\">approved</span>"
                            "<span class=\"muted\">-</span>")
                        (case (:disposition r)
                          :commit "<span class=\"ok\">commit</span>"
                          :hold "<span class=\"critical\">hold</span>"
                          :escalate "<span class=\"warn\">escalate</span>"
                          (str "<span class=\"muted\">" (esc (cell (:disposition r))) "</span>")))))))

(defn- ledger-section [ledger]
  (section "Audit ledger (append-only)"
           (str "The store's own <code>ledger</code> after the run &mdash; the only three fact types "
                "<code>sportsgoodsmfg.operation</code> ever appends. "
                "<code>:approval-requested</code>/<code>:approval-granted</code> live on the "
                "in-memory <code>:audit</code> channel only and are intentionally absent here.")
           (table ["#" "Fact" "Op" "Subject" "Actor" "Basis"]
                  (map-indexed
                   (fn [i f]
                     (tr (str (inc i))
                         (case (:t f)
                           :committed "<span class=\"ok\">committed</span>"
                           :governor-hold "<span class=\"critical\">governor-hold</span>"
                           :approval-rejected "<span class=\"critical\">approval-rejected</span>"
                           (esc (nm (:t f))))
                         (str "<code>" (esc (nm (:op f))) "</code>")
                         (str "<code>" (esc (cell (:subject f))) "</code>")
                         (esc (cell (:actor f)))
                         (esc (join-names (:basis f)))))
                   ledger))))

(def ^:private css
  (str "body{font:14px/1.5 -apple-system,BlinkMacSystemFont,'Helvetica Neue',sans-serif;"
       "margin:0;color:#1a1a1a;background:#f5f5f5}"
       ".bar{background:#1b2b22;color:#fff;padding:1.2rem 2rem}"
       ".bar h1{margin:0;font-size:1.15rem}.bar p{margin:.35rem 0 0;font-size:.8rem;opacity:.75}"
       "main{max-width:1180px;margin:1.5rem auto;padding:0 1rem}"
       ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;"
       "box-shadow:0 1px 3px rgba(0,0,0,.08)}"
       ".card h2{margin:0 0 .5rem;font-size:1rem}"
       ".muted{color:#777;font-size:.82rem}"
       "table{border-collapse:collapse;width:100%;font-size:.85rem;margin-top:.6rem}"
       "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee;vertical-align:top}"
       "th{font-weight:600;color:#555}"
       ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}"
       "code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"
       "footer{max-width:1180px;margin:0 auto 2rem;padding:0 1rem;font-size:.78rem;color:#777}"))

(defn render [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))]
    (str "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>Sports goods plant operations console &mdash; cloud-itonami-isic-3230</title>"
         "<style>" css "</style></head><body>\n"
         "<header class=\"bar\"><h1>Sports goods plant operations (ISIC 3230) &mdash; "
         "<code>sportsgoodsmfg</code></h1>"
         "<p>Generated by <code>sportsgoodsmfg.render-html</code> from a real "
         "<code>sportsgoodsmfg.operation</code> actor run. No hand-written domain data.</p></header>\n"
         "<main>\n  "
         (str/join "\n  " [(gate-section)
                           (batches-section db ledger)
                           (equipment-section db ledger)
                           (maintenance-section db)
                           (shipments-section db)
                           (concerns-section db)
                           (holds-section ledger)
                           (runs-section runs)
                           (ledger-section ledger)])
         "\n</main>\n<footer>"
         (count runs) " actor runs &middot; " (count ledger) " ledger facts &middot; "
         (count (filter #(= :governor-hold (:t %)) ledger)) " governor HARD holds &middot; "
         (count (store/maintenance-history db)) " maintenance drafts &middot; "
         (count (store/shipment-history db)) " shipment drafts"
         "</footer>\n</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)
        f (java.io.File. ^String out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f html)
    (println "wrote" out
             (str "(" (count (:runs result)) " actor runs, "
                  (count (store/ledger (:db result))) " ledger facts, "
                  (count (filter #(= :governor-hold (:t %)) (store/ledger (:db result))))
                  " HARD holds)"))))
