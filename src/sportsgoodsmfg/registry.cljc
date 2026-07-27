(ns sportsgoodsmfg.registry
  "Pure-function domain logic for the sports-goods plant-operations
  coordination actor -- equipment/batch verification, shipment-
  quantity recompute, product-type validation, impact-protection-
  rating plausibility validation, batch-weight plausibility
  validation, defect-rate plausibility validation, and draft
  maintenance-schedule/shipment-coordination record construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has NO
  pre-existing `kotoba-lang/sportsgoodsmfg`-style capability library
  to wrap (verified: no such repo exists, and no `sport`/`sports`-
  named manufacturing-capability repo exists in kotoba-lang either,
  via GitHub code/repo search). The domain logic therefore lives here
  as pure functions, re-verified INDEPENDENTLY by
  `sportsgoodsmfg.governor` -- the same 'ground truth, not self-
  report' discipline every sibling actor's own registry establishes
  (most directly `jewellerymfg.registry` from `cloud-itonami-isic-
  3211`, this vertical's closest domain analog): never trust a
  proposal's own self-reported quantity/status when the inputs needed
  to recompute it independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real plant-operations system. It builds the DRAFT
  record a plant coordinator would keep (a scheduled equipment
  maintenance window, a coordinated shipment), not the act of
  actuating molding/assembly/finishing-line equipment or dispatching
  a real freight carrier, and never the act of self-issuing a
  product-safety/impact-protection compliance certification (this
  actor NEVER does any of those -- see README `What this actor does
  NOT do`).

  SCOPE: ISIC 3230 covers manufacture of sports goods -- balls,
  rackets, protective gear, and fitness equipment produced on
  molding/assembly/finishing lines. This actor coordinates the
  back-office record-keeping around that plant (production-batch
  logging, maintenance scheduling, safety-concern flagging, shipment
  coordination) -- it never touches the molding/assembly-line
  equipment directly, and it never stands in for the product-safety-
  certification authority (e.g. an accredited testing/certification
  body) that certifies a product's compliance with an applicable
  impact-protection/product-safety standard.")

;; ----------------------------- constants -----------------------------

(def valid-product-types
  "The closed set of product-type values a production-batch record may
  declare -- the sports goods routinely molded/assembled/finished on
  a plant's production lines. Anything else is a fabricated/
  unrecognized product type -- the governor HARD-holds rather than
  let an invented classification pass through. This actor never
  DECIDES a batch's product type (that is the plant's own production-
  intake function); it only validates that a batch record declares
  one of the real, known values."
  #{:ball :racket :protective-gear :fitness-equipment})

(def impact-rating-percent-min
  "Physical floor for a batch's own impact-protection compliance
  reading, expressed as a percentage of the applicable impact-
  protection/product-safety standard's own pass threshold (e.g. the
  attenuation/compliance percentage a testing lab would report for a
  protective-gear or ball batch). A reading of 0 or below is not a
  real compliance-test result."
  1)

(def impact-rating-percent-max
  "Physical ceiling for a batch's own impact-protection compliance
  reading. 100 (100% of the applicable standard's own pass threshold)
  is the highest compliance percentage any real accredited test
  reports -- a claimed reading beyond 100 is not a real, certifiable
  compliance result and is beyond what any real accredited test can
  substantiate."
  100)

(def weight-grams-min
  "Physical floor for a batch's own total finished-goods weight in
  grams -- a batch must weigh strictly more than zero; zero/negative
  weight is not a real molded/assembled/finished batch."
  0.0)

(def weight-grams-max
  "Physical ceiling for a batch's own total finished-goods weight in
  grams for a single production batch -- 1,000,000g (1000kg / one
  tonne) comfortably exceeds any plausible single plant production
  batch across this vertical's product types (e.g. ~200 sets of 5kg
  adjustable dumbbells is already a large fitness-equipment batch); a
  reading beyond this is implausible sensor/scale data, not a real
  batch."
  1000000.0)

(def defect-rate-min-percent
  "Physical floor for a batch's own molding/assembly/finishing
  defect-rate reading (zero defects is the best possible outcome,
  never negative)."
  0.0)

(def defect-rate-max-percent
  "Physical ceiling for a batch's own defect-rate reading -- a batch
  cannot reject more than 100% of its own output. A reading above this
  is implausible sensor/QC data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its product-type/impact-rating/weight/defect-rate claims have
  actually been QC-inspected, not merely logged from an unverified
  intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the
  plant's production ledger? Coordinating a shipment against a batch
  that is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-quantity-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-units` + `new-units` exceed `batch`'s own recorded
  `:quantity-units` (the batch's own logged production quantity)?
  Needs no proposal inspection or stored-verdict lookup -- its inputs
  are permanent fields already on the batch's own record, the same
  shape every sibling actor's own cost/total-matching check uses."
  [batch new-units]
  (let [capacity (:quantity-units batch)
        so-far (:shipped-units batch 0.0)]
    (and (number? capacity)
         (number? new-units)
         (number? so-far)
         ;; Compared at 1/10000 of a unit, not on raw doubles. A shipment
         ;; that fills a batch EXACTLY to its recorded capacity is legal,
         ;; and comparing the raw sum flagged such shipments as over
         ;; because the sum is not the double nearest the true total.
         (> (Math/round (* 10000 (+ (double so-far) (double new-units))))
            (Math/round (* 10000 (double capacity))))))) 

(defn shipment-quantity-exceeded-checkable?
  "Can `batch`'s headroom actually be computed for `new-units`?

  `shipment-quantity-exceeded?` answers only `over` / `not over`, and its
  `(and (number? ...) ...)` guard made every un-checkable case fall
  through as `not over` -- a batch with no recorded capacity, or a
  shipment stating no amount, passed the over-capacity check silently.
  Callers must ask this first: un-checkable is not headroom."
  [batch new-units]
  (boolean (and (map? batch)
                (number? (:quantity-units batch))
                (number? (:shipped-units batch 0.0))
                (number? new-units))))

(defn product-type-valid?
  "Is `product-type` one of the closed, known sports-goods values?
  nil/blank is treated as invalid (a production-batch patch must
  declare a real product type, not omit it silently)."
  [product-type]
  (contains? valid-product-types product-type))

(defn impact-rating-percent-valid?
  "Is `rating` a physically/certifiably plausible impact-protection
  compliance reading, expressed as a percentage of the applicable
  standard's own pass threshold? Rejects nil, non-integers, values
  below `impact-rating-percent-min`, and values beyond
  `impact-rating-percent-max` -- a fabricated or sensor-error reading,
  never let through as a real compliance-test fact."
  [rating]
  (and (integer? rating)
       (>= rating impact-rating-percent-min)
       (<= rating impact-rating-percent-max)))

(defn weight-grams-valid?
  "Is `weight` a physically plausible batch finished-goods-weight
  reading, in grams? Rejects nil, non-numbers, values at or below
  `weight-grams-min`, and values beyond `weight-grams-max` -- a
  fabricated or scale-error reading, never let through as a real
  batch fact."
  [weight]
  (and (number? weight)
       (> (double weight) weight-grams-min)
       (<= (double weight) weight-grams-max)))

(defn defect-rate-valid?
  "Is `percent` a physically plausible batch molding/assembly/
  finishing defect-rate reading? Rejects nil, non-numbers, negative
  values, and values beyond `defect-rate-max-percent` -- a fabricated
  or sensor-error reading, never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) defect-rate-min-percent)
       (<= (double percent) defect-rate-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's. And NEVER a product-safety/impact-protection compliance
  certification -- this actor is never the product-safety-
  certification authority (see README `What this actor does NOT
  do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  molding/assembly/finishing-equipment maintenance window against a
  verified, registered piece of equipment. Pure function -- does not
  actuate the molding/assembly-line equipment or execute any
  maintenance; it builds the RECORD a plant coordinator would keep.
  `sportsgoodsmfg.governor` independently re-verifies the equipment's
  own verified/registered ground truth, and permanently blocks any
  attempt to directly actuate molding/assembly-line equipment (see
  README `Actuation`), before this is ever allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound sports-goods shipment against a verified, registered
  production batch. Pure function -- does not dispatch any real
  freight carrier; it builds the RECORD a plant coordinator would
  keep. `sportsgoodsmfg.governor` independently re-verifies the
  shipment's own claimed quantity against `shipment-quantity-
  exceeded?`, before this is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
