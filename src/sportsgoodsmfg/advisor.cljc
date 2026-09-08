(ns sportsgoodsmfg.advisor
  "SportsGoodsAdvisor -- the *contained intelligence node* for the
  sports-goods plant-operations coordination actor.

  It normalizes production-batch patches (product-type/impact-rating-
  percent/weight-grams/defect-rate), drafts a molding/assembly/
  finishing-equipment maintenance scheduling proposal against a piece
  of equipment, drafts a safety-concern flag, and drafts an outbound
  sports-goods shipment coordination proposal against a production
  batch. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a real molding/assembly-line-equipment
  actuation, freight dispatch, or product-safety/impact-protection
  compliance certification -- see README `What this actor does NOT
  do`. Every output is censored downstream by `sportsgoodsmfg.governor`
  before anything touches the SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `sportsgoodsmfg.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:batch/upsert :maintenance/schedule
                                 ; :safety-concern/flag
                                 ; :shipment/propose} propose-shaped
                                 ; effects, NEVER a direct molding/
                                 ; assembly-line-equipment-control
                                 ; effect
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) --
  `sportsgoodsmfg.governor` HARD-holds any request that doesn't, so a
  mis-wired caller can never reach a commit path even if this advisor
  were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [sportsgoodsmfg.registry :as registry]
            [sportsgoodsmfg.store :as store]
            [langchain.model :as model]))

(defn- log-production-batch
  "Production-batch intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the batch's product-type,
  impact-rating-percent, weight-grams, or defect-rate, nor its
  verification status. High confidence, low stakes -- administrative
  logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "production batch record updated: " (pr-str (keys patch)))
   :rationale  "normalizes the input patch only. no new facts are invented."
   :cites      (vec (keys patch))
   :effect     :batch/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-maintenance
  "Draft a molding/assembly/finishing-equipment maintenance-window
  scheduling proposal against a piece of equipment. The advisor
  reports what it can see (equipment verified?/registered?) in its
  rationale, but `sportsgoodsmfg.governor` NEVER trusts this report --
  it independently re-derives verified?/registered? from the
  equipment's own stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (store/equipment-unit db equipment-id)
        ready? (and eq (registry/equipment-ready? eq))]
    {:summary    (str subject " maintenance-window proposal (" (:maintenance-type value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (if eq
                   (str "equipment-verified?=" (registry/equipment-verified? eq)
                        " equipment-registered?=" (registry/equipment-registered? eq)
                        " actuate-equipment?=" (boolean (:actuate-equipment? value)))
                   (str equipment-id " not found"))
     :cites      (if eq [equipment-id] [])
     :effect     :maintenance/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:actuate-equipment? value))) 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a materials-safety/product-safety-standard (e.g. impact-
  protection rating) concern. ALWAYS `:stake :coordination/safety-
  concern` -- a safety concern is NEVER a proposal the advisor may
  quietly downgrade to low-stakes, and it is never gated on the
  referenced equipment/batch being verified (a concern can be raised
  about ANY equipment or batch, verified or not -- see README `What
  this actor does NOT do` re: never blocking safety-relevant reporting
  on an administrative technicality). See `sportsgoodsmfg.phase`: no
  phase ever adds this op to a phase's `:auto` set;
  `sportsgoodsmfg.governor` also always escalates on
  `:coordination/safety-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject value]}]
  (let [equipment-id (:equipment-id value)
        eq (and equipment-id (store/equipment-unit db equipment-id))]
    {:summary    (str subject " safety concern (" (:severity value) ")"
                      (when eq (str " equipment=" equipment-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if eq [equipment-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- coordinate-shipment
  "Draft an outbound sports-goods shipment coordination proposal
  against a production batch. The advisor passes through the caller's
  own claimed unit quantity -- it does NOT invent one, and
  `sportsgoodsmfg.governor` NEVER trusts it: it independently
  recomputes whether the batch's own cumulative-shipped quantity plus
  this claim would exceed the batch's own recorded quantity before any
  commit is possible."
  [db {:keys [subject value]}]
  (let [batch-id (:batch-id value)
        b (store/batch db batch-id)
        ready? (and b (registry/batch-ready? b))
        over-quantity? (and b (registry/shipment-quantity-exceeded?
                               b (:units value)))]
    {:summary    (str subject " shipment-coordination proposal ("
                      (:units value) " units)"
                      (when b (str " batch=" batch-id)))
     :rationale  (if b
                   (str "batch-verified?=" (registry/batch-verified? b)
                        " batch-registered?=" (registry/batch-registered? b)
                        " over-quantity?=" over-quantity?)
                   (str batch-id " not found"))
     :cites      (if b [batch-id] [])
     :effect     :shipment/propose
     :value      value
     :stake      nil
     :confidence (if (and ready? (not over-quantity?)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-production-batch      (log-production-batch db request)
    :schedule-maintenance      (schedule-maintenance db request)
    :flag-safety-concern       (flag-safety-concern db request)
    :coordinate-shipment       (coordinate-shipment db request)
    {:summary "unsupported operation" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "You are the advisor for a sports-goods plant-operations "
       "coordinator. Based only on the given facts, return exactly one "
       "proposal as an EDN map. Do not write any explanation or preamble, "
       "output EDN only.\n"
       "Keys: :summary (human-facing draft) :rationale (basis/always from "
       "facts) :cites (vector of fact keys used) "
       ":effect (:batch/upsert|:maintenance/schedule|"
       ":safety-concern/flag|:shipment/propose) "
       ":stake (:coordination/safety-concern or nil) :confidence (0..1).\n"
       "IMPORTANT: never propose work against unverified or unregistered "
       "equipment or batches. Never propose direct actuation of molding/"
       "assembly-line equipment (this actor only proposes, it never "
       "executes). Never propose self-issuing a product-safety/impact-"
       "protection compliance certification. Never misreport shipment "
       "quantity."))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-production-batch       {:batch (store/batch st subject)}
    :schedule-maintenance       {:equipment (store/equipment-unit st (:equipment-id value))}
    :flag-safety-concern        {:equipment (and (:equipment-id value)
                                                  (store/equipment-unit st (:equipment-id value)))}
    :coordinate-shipment        {:batch (store/batch st (:batch-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `sportsgoodsmfg.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule maintenance,
  auto-flag a concern, or auto-coordinate a shipment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "could not interpret LLM response" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "operation: " (:op req)
                                              "\nsubject: " (:subject req)
                                              "\nfacts: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :sportsgoodsmfg-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
