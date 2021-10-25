(ns athens.common-events.resolver.atomic
  (:require
    [athens.common-db              :as common-db]
    [athens.common-events.resolver :as resolver]
    [athens.common.logging         :as log]
    [athens.common.utils           :as utils]
    [athens.dates                  :as dates]
    [clojure.pprint                :as pp]
    [clojure.string                :as s]))


(defmulti resolve-atomic-op-to-tx
  "Resolves ⚛️ Atomic Graph Ops to TXs."
  #(:op/type %2))


(defmethod resolve-atomic-op-to-tx :block/new
  [db {:op/keys [args]}]
  (let [{:keys [block-uid
                position]}              args
        {:keys [ref-uid
                relation]}              position
        ref-parent?                     (or (int? relation)
                                            (#{:first :last} relation))
        ref-block-exists?               (int? (common-db/e-by-av db :block/uid ref-uid))
        ref-block                       (when ref-block-exists?
                                          (common-db/get-block db [:block/uid ref-uid]))
        {parent-block-uid :block/uid
         :as              parent-block} (if ref-parent?
                                          (if ref-block-exists?
                                            ref-block
                                            {:block/uid ref-uid})
                                          (common-db/get-parent db [:block/uid ref-uid]))
        parent-block-exists?            (int? (common-db/e-by-av db :block/uid parent-block-uid))
        new-block-order                 (condp = relation
                                          :first  0
                                          :last   (->> parent-block
                                                       :block/children
                                                       (map :block/order)
                                                       (reduce max 0)
                                                       inc)
                                          :before (:block/order ref-block)
                                          :after  (inc (:block/order ref-block))
                                          (inc relation))
        now                             (utils/now-ts)
        new-block                       {:block/uid    block-uid
                                         :block/string ""
                                         :block/order  new-block-order
                                         :block/open   true
                                         :create/time  now
                                         :edit/time    now}
        reindex                         (if-not parent-block-exists?
                                          [new-block]
                                          (concat [new-block]
                                                  (common-db/inc-after db
                                                                       [:block/uid parent-block-uid]
                                                                       (dec new-block-order))))
        tx-data                         [{:block/uid      parent-block-uid
                                          :block/children reindex
                                          :edit/time      now}]]
    tx-data))


;; This is Atomic Graph Op, there is also composite version of it
(defmethod resolve-atomic-op-to-tx :block/save
  [db {:op/keys [args]}]
  (let [{:keys [block-uid
                new-string
                old-string]} args
        stored-old-string    (if-let [block-eid (common-db/e-by-av db :block/uid block-uid)]
                               (common-db/v-by-ea db block-eid :block/string)
                               "")]
    (when-not (= stored-old-string old-string)
      (print (ex-info ":block/save operation started from a stale state."
                      {:op/args           args
                       :actual-old-string stored-old-string})))
    (let [now           (utils/now-ts)
          updated-block {:block/uid    block-uid
                         :block/string new-string
                         :edit/time    now}]
      [updated-block])))


(defmethod resolve-atomic-op-to-tx :block/remove
  [db {:op/keys [args]}]
  ;; [x] :db/retractEntity
  ;; [x] retract children
  ;; [x] :db/retract parent's child
  ;; [x] reindex parent's children
  ;; [x] cleanup block refs
  (let [{:keys [block-uid]}   args
        block-exists?         (common-db/e-by-av db :block/uid block-uid)
        {removed-order :block/order
         children      :block/children
         :as           block} (when block-exists?
                                (common-db/get-block db [:block/uid block-uid]))
        parent-eid            (when block-exists?
                                (common-db/get-parent-eid db [:block/uid block-uid]))
        parent-uid            (when parent-eid
                                (common-db/v-by-ea db parent-eid :block/uid))
        reindex               (common-db/dec-after db [:block/uid parent-uid] removed-order)
        reindex?              (seq reindex)
        has-kids?             (seq children)
        descendants-uids      (when has-kids?
                                (loop [acc        []
                                       to-look-at children]
                                  (if-let [look-at (first to-look-at)]
                                    (let [c-uid   (:block/uid look-at)
                                          c-block (common-db/get-block db [:block/uid c-uid])]
                                      (recur (conj acc c-uid)
                                             (apply conj (rest children)
                                                    (:block/children c-block))))
                                    acc)))
        all-uids-to-remove    (conj (set descendants-uids) block-uid)
        uid->refs             (->> all-uids-to-remove
                                   (map (fn [uid]
                                          (let [block    (common-db/get-block db [:block/uid uid])
                                                rev-refs (set (:block/_refs block))]
                                            (when-not (empty? rev-refs)
                                              [uid (set rev-refs)]))))
                                   (remove nil?)
                                   (into {}))
        ref-eids              (mapcat second uid->refs)
        eids->uids            (->> ref-eids
                                   (map (fn [{id :db/id}]
                                          [id (common-db/v-by-ea db id :block/uid)]))
                                   (into {}))
        removed-uid->uid-refs (->> uid->refs
                                   (map (fn [[k refs]]
                                          [k (set
                                               (for [{eid :db/id} refs
                                                     :let         [uid (eids->uids eid)]
                                                     :when        (not (contains? all-uids-to-remove uid))]
                                                 uid))]))
                                   (remove #(empty? (second %)))
                                   (into {}))
        asserts               (->> removed-uid->uid-refs
                                   (mapcat (fn [[removed-uid referenced-uids]]
                                             (let [removed-string (common-db/v-by-ea db [:block/uid removed-uid] :block/string)
                                                   from-string    (str "((" removed-uid "))")
                                                   uid->string    (->> referenced-uids
                                                                       (map (fn [uid]
                                                                              [uid (common-db/v-by-ea db [:block/uid uid] :block/string)]))
                                                                       (into {}))]
                                               (map (fn [[uid original-string]]
                                                      {:block/uid    uid
                                                       :block/string (s/replace original-string from-string removed-string)})
                                                    uid->string)))))
        has-asserts?          (seq asserts)
        retract-kids          (mapv (fn [uid]
                                      [:db/retractEntity [:block/uid uid]])
                                    descendants-uids)
        retract-entity        (when block-exists?
                                [:db/retractEntity [:block/uid block-uid]])
        retract-parents-child (when parent-uid
                                [:db/retract [:block/uid parent-uid] :block/children [:block/uid block-uid]])
        parent                (when reindex?
                                {:block/uid      parent-uid
                                 :block/children reindex})
        txs                   (when block-exists?
                                (cond-> []
                                  parent-uid   (conj retract-parents-child)
                                  reindex?     (conj parent)
                                  has-kids?    (into retract-kids)
                                  has-asserts? (into asserts)
                                  true         (conj retract-entity)))]
    (log/debug ":block/remove block-uid:" (pr-str block-uid)
               "\nblock:" (with-out-str
                            (pp/pprint block))
               "\nparent-eid:" (pr-str parent-eid)
               "\nparent-uid:" (pr-str parent-uid)
               "\nretract-kids:" (pr-str retract-kids)
               "\nresolved to txs:" (with-out-str
                                      (pp/pprint txs)))
    txs))


(defmethod resolve-atomic-op-to-tx :page/new
  [db {:op/keys [args]}]
  (let [{:keys [page-uid
                title]} args
        page-exists?    (common-db/e-by-av db :node/title title)
        now             (utils/now-ts)
        page            {:node/title     title
                         :block/uid      page-uid
                         :block/children []
                         :create/time    now
                         :edit/time      now}
        current-uid     (common-db/get-page-uid-by-title db title)
        txs             (cond
                          (not= (-> title dates/title-to-date dates/date-to-day)
                                (-> page-uid dates/uid-to-date dates/date-to-day))
                          (throw (ex-info "Page title and uid must match for Daily page."
                                          {:title    title
                                           :page-uid page-uid}))

                          (and page-exists?
                               (not= page-uid current-uid))
                          (throw (ex-info "Page title already exists with different page uid."
                                          {:page-uid    page-uid
                                           :current-uid current-uid}))

                          page-exists?
                          []

                          :else
                          [page])]
    txs))


(defmethod resolve-atomic-op-to-tx :composite/consequence
  [db {:op/keys [consequences] :as _composite}]
  (into []
        (mapcat (fn [consequence]
                  (resolve-atomic-op-to-tx db consequence))
                consequences)))


(defn resolve-to-tx
  [db {:event/keys [type op] :as event}]
  (if (contains? #{:op/atomic} type)
    (resolve-atomic-op-to-tx db op)
    (resolver/resolve-event-to-tx db event)))