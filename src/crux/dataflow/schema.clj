(ns crux.dataflow.schema
  (:require [clojure.tools.logging :as log]
            [crux.codec :as c]
            [clojure.walk :as w]
            [crux.query :as q]
            [crux.dataflow.misc-helpers :as fm]
            [clj-3df.attribute :as attribute])
  (:import (java.util Date)))

(def test-schema
  #:user{:name {:db/valueType :String,
                :query_support "AdaptiveWCO",
                :index_direction "Both",
                :input_semantics "CardinalityOne",
                :trace_slack {:TxId 1}},
         :email {:db/valueType :String,
                 :query_support "AdaptiveWCO",
                 :index_direction "Both",
                 :input_semantics "CardinalityOne",
                 :trace_slack {:TxId 1}},
         :knows {:db/valueType :Eid,
                 :query_support "AdaptiveWCO",
                 :index_direction "Both",
                 :input_semantics "CardinalityMany",
                 :trace_slack {:TxId 1}},
         :likes {:db/valueType :String,
                 :query_support "AdaptiveWCO",
                 :index_direction "Both",
                 :input_semantics "CardinalityMany",
                 :trace_slack {:TxId 1}}})

; db input_semantics doesn't mean what you think it means
; they are more like write semantics
; see https://github.com/comnik/declarative-dataflow/issues/85
; see https://github.com/sixthnormal/clj-3df/issues/45
(defn- validate-value-type! [value-type v]
  (assert
    (case value-type
      :String (string? v)
      :Number (number? v)
      :Bool (boolean? v)
      :Eid (c/valid-id? v)
      :Aid (keyword? v)
      :Instant (instance? Date v)
      :Uuid (uuid? v)
      :Real (float? v))
    (str "Invalid value type: " value-type " " (pr-str v))))

(defn attr-is-card-one? [attr-conf]
  (= "CardinalityOne" (:input_semantics attr-conf)))


(defn- attr-def [attr-type & [collection-type opts]]
  (merge
    (if collection-type
      {::collection-type collection-type})
    (attribute/of-type attr-type)
    (attribute/input-semantics (if (= ::set collection-type)
                                 :db.semantics.cardinality/many
                                 :db.semantics.cardinality/one))
    (attribute/tx-time)))

; Raw
; No special semantics enforced. Source is responsible for everything.

; CardinalityOne
; Only a single value per eid is allowed at any given timestamp.
; causes panic
; see https://github.com/sixthnormal/clj-3df/issues/45

; CardinalityMany
; Multiple different values for any given eid are allowed, but (e,v) pairs are enforced to be distinct.
; so useful for sets, but will destroy lists
(defn inflate [local-schema]
  (reduce-kv
    (fn [m attr-name attr-semantics]
      (assoc m attr-name (apply attr-def attr-semantics)))
    {}
    local-schema))

(defn- validate-schema! [flat-schema {:keys [crux.db/id] :as doc}]
  (assert (c/valid-id? id))
  (assert (map? doc))
  (doseq [[k v] (dissoc doc :crux.db/id)
          :let [{:keys [db/valueType input_semantics]} (get flat-schema k)]]
    (assert (contains? flat-schema k))
    (if (coll? v)
      (doseq [item v]
        (validate-value-type! valueType item))
      (validate-value-type! valueType v))))

(defn matches-schema? [flat-schema doc]
  (try
    (validate-schema! flat-schema doc)
    true
    (catch AssertionError e
      (log/debug e "Does not match flat-schema:")
      false)))

(defn encode-id [v]
  (if (and (or (string? v) (keyword? v)) (c/valid-id? v))
    (str "#crux/id "(pr-str v))
    v)) ; todo case type

(defn maybe-encode-id [flat-schema attr-name v]
  (if (and (or (= :crux.db/id attr-name)
               (= :Eid (get-in flat-schema [attr-name :db/valueType]))))
    (if (coll? v) ; todo check on maps
      (into (empty v) (map encode-id v))
      (encode-id v))
    v))

(defn maybe-decode-id [v]
  (if (string? v)
    (try
      (c/read-edn-string-with-readers v)
      (catch Exception _ v))
    v))

(defn encode-query-ids [schema clauses]
  (w/postwalk
    (fn [x]
      (if (and (vector? x) (= 3 (count x)))
        (let [[e a v] x]
          [e a (maybe-encode-id schema a v)])
        x))
    clauses))

(defn decode-result-ids [results]
  (w/postwalk
    (fn [x]
      (if (and (map? x) (= [:Eid] (keys x)))
        (update x :Eid maybe-decode-id)
        x))
    results))

(defn- auto-encode-eids [doc schema]
  (reduce-kv
    (fn [m k v] (assoc m k (maybe-encode-id schema k v)))
    {}
    doc))

(defn prepare-map-for-3df [schema {:keys [crux.db/id] :as crux-query-result-map}]
  (assert (map? crux-query-result-map))
  (-> crux-query-result-map
      (assoc :db/id (encode-id id))
      (dissoc :crux.db/id)
      (auto-encode-eids schema)))

(defn prepare-query [schema query]
  (-> (q/normalize-query query)
      (update :where #(encode-query-ids schema %))
      (update :rules #(encode-query-ids schema %))))

(defn- assoc-entity-name [ent-key entity-schema]
  (fm/map-values
    #(assoc % :crux.dataflow/entity ent-key)
    entity-schema))

(defn calc-flat-schema [full-schema]
  (apply merge (vals full-schema)))

(defn calc-full-schema [schema]
  (let [inflated (fm/map-values inflate schema)]
    (fm/map-values assoc-entity-name inflated true)))

(assert
  (= {:user/name {:db/valueType :String,
                  :query_support "AdaptiveWCO",
                  :index_direction "Both",
                  :input_semantics "CardinalityOne",
                  :trace_slack {:TxId 1},
                  :crux.dataflow/entity :user},
      :user/email {:db/valueType :String,
                   :query_support "AdaptiveWCO",
                   :index_direction "Both",
                   :input_semantics "CardinalityOne",
                   :trace_slack {:TxId 1},
                   :crux.dataflow/entity :user},
      :task/title {:db/valueType :String,
                   :query_support "AdaptiveWCO",
                   :index_direction "Both",
                   :input_semantics "CardinalityOne",
                   :trace_slack {:TxId 1},
                   :crux.dataflow/entity :task}})
  (calc-flat-schema
    (calc-full-schema
      {:user {:user/name [:String]
              :user/email [:String]}
       :task {:task/title [:String]}})))

(assert
  (= {:user/name "Patrik",
      :user/knows ["#crux/id :ids/bart"],
      :user/likes ["apples" "daples"],
      :user/email "iifojweiwei",
      :db/id "#crux/id :patrik"}
     (let [args [test-schema
                 {:crux.db/id :patrik,
                  :user/name "Patrik",
                  :user/knows [:ids/bart],
                  :user/likes ["apples" "daples"],
                  :user/email "iifojweiwei"}]]
       (apply prepare-map-for-3df args))))

(assert
  (= #:user{:email {:db/valueType :String,
                    :query_support "AdaptiveWCO",
                    :index_direction "Both",
                    :input_semantics "CardinalityOne",
                    :trace_slack {:TxId 1}},
            :name {:db/valueType :String,
                   :query_support "AdaptiveWCO",
                   :index_direction "Both",
                   :input_semantics "CardinalityOne",
                   :trace_slack {:TxId 1}},
            :knows {:crux.dataflow.schema/collection-type :crux.dataflow.schema/set,
                    :db/valueType :Eid,
                    :query_support "AdaptiveWCO",
                    :index_direction "Both",
                    :input_semantics "CardinalityMany",
                    :trace_slack {:TxId 1}}}
     (inflate
       {:user/email [:String]
        :user/name [:String]
        :user/knows [:Eid ::set]})))

