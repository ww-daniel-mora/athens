(ns athens.common-events.graph.schema
  (:require
    [malli.core  :as m]
    [malli.error :as me]
    [malli.util  :as mu]))


(def atomic-op-types
  [:enum
   :block/new ; ✓
   :block/save ; ✓
   :block/open
   :block/remove ; ✓
   :block/move
   :page/new ; ✓
   :page/rename
   :page/merge
   :page/remove
   :shortcut/new
   :shortcut/remove
   :shortcut/move])


(def op-type-atomic-common
  [:map
   [:op/type atomic-op-types]
   [:op/atomic? true?]])


(def op-block-new
  [:map
   [:op/args
    [:map
     [:block-uid string?]
     [:position [:map
                 [:ref-uid string?]
                 [:relation [:or int? [:enum
                                       :before
                                       :after
                                       :first
                                       :last]]]]]]]])


(def op-block-save
  [:map
   [:op/args
    [:map
     [:block-uid string?]
     [:new-string string?]
     [:old-string string?]]]])


(def op-block-remove
  [:map
   [:op/args
    [:map
     [:block-uid string?]]]])


(def op-page-new
  [:map
   [:op/args
    [:map
     [:title string?]
     [:page-uid string?]]]])


(def atomic-op
  [:schema
   {:registry
    {::atomic-op    [:multi {:dispatch :op/type}
                     [:block/new (mu/merge
                                   op-type-atomic-common
                                   op-block-new)]
                     [:block/save (mu/merge
                                    op-type-atomic-common
                                    op-block-save)]
                     [:block/remove (mu/merge
                                      op-type-atomic-common
                                      op-block-remove)]
                     [:page/new (mu/merge
                                  op-type-atomic-common
                                  op-page-new)]
                     [:composite/consequence [:ref ::composite-op]]]
     ::composite-op [:map
                     [:op/type [:enum :composite/consequence]]
                     [:op/atomic? false?]
                     [:op/trigger map?]
                     [:op/consequences [:sequential [:ref ::atomic-op]]]]}}
   ::atomic-op])


(def valid-atomic-op?
  (m/validator atomic-op))


(defn explain-atomic-op
  [data]
  (-> atomic-op
      (m/explain data)
      (me/humanize)))