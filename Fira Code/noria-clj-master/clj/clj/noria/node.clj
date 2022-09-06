(ns noria.node
  (:require [noria.thunks :as t]
            [noria.macros :as macros]
            [clojure.spec.alpha :as s])
  (:import [noria LCS Reconciler]
           [gnu.trove TLongHashSet TLongArrayList TObjectLongHashMap]))

(s/def :noria/node nat-int?)
(s/def :noria/update-type #{:add :remove :make-node :set-attr :destroy})
(s/def :noria/attr keyword?)
(s/def :noria/value any?)
(s/def :noria/index nat-int?)
(s/def :noria/type keyword?)
(s/def :noria/key any?)
(s/def :noria/constructor-parameters (s/map-of keyword? any?))

(defmulti update-spec :noria/update-type)
(defmethod update-spec :add [_]
  (s/keys :req [:noria/node :noria/attr :noria/value :noria/index]))
(defmethod update-spec :remove [_]
  (s/keys :req [:noria/node :noria/attr :noria/value]))
(defmethod update-spec :make-node [_]
  (s/keys :req [:noria/node :noria/type :noria/constructor-parameters]))
(defmethod update-spec :set-attr [_]
  (s/keys :req [:noria/node :noria/attr :noria/value]))
(defmethod update-spec :destroy [_]
  (s/keys :req [:noria/node]))

(s/def :noria/update (s/multi-spec update-spec :noria/update-type))


(defonce schema (atom {:constructors {}
                       :attrs {}}))

(defn data-type [attr]
  (or (:noria/data-type (-> @schema (get :attrs) (get attr))) :simple-value))

(defn seq-kind [attr]
  (or (:noria/seq-kind (-> @schema (get :attrs) (get attr))) :vector))

(defn defattr [attr data]
  (swap! schema assoc-in [:attrs attr] data))

(defn constructor-parameters [node-type]
  (-> @schema
      (get :constructors)
      (get node-type)
      (get :attrs #{})))

(defn default-values [node-type]
  (get-in @schema [:constructors node-type :default-values] {}))

(defn defconstructor [node-type {:keys [attrs default-values] :as opts}]
  (swap! schema assoc-in [:constructors node-type] {:attrs attrs
                                                    :default-values default-values}))


(defn update-order [parent-node attr ^TLongArrayList old-nodes ^TLongArrayList new-nodes]
  (if (= old-nodes new-nodes)
    nil
    (let [lcs (TLongHashSet. (LCS/lcs (.toNativeArray old-nodes) (.toNativeArray new-nodes)))
          res (java.util.ArrayList.)]
      (dotimes [i (.size old-nodes)]
        (let [node (.get old-nodes i)]
          (when-not (.contains lcs node)
            (.add res {:noria/update-type :remove
                       :noria/attr attr
                       :noria/node parent-node
                       :noria/value node}))))
      (dotimes [i (.size new-nodes)]
        (let [node (.get new-nodes i)]
          (when-not (.contains lcs node)
            (.add res {:noria/update-type :add
                       :noria/attr attr
                       :noria/node parent-node
                       :noria/value node
                       :noria/index i}))))
      res)))

(defn update-set [parent-node attr ^TLongHashSet old-nodes ^TLongHashSet new-nodes]
  (let [updates (java.util.ArrayList.)
        i (volatile! 0)]
    (.forEach old-nodes (reify gnu.trove.TLongProcedure
                          (^boolean execute [_ ^long node]
                           (when-not (.contains new-nodes node)
                             (.add updates {:noria/update-type :remove
                                            :noria/attr attr
                                            :noria/node parent-node
                                            :noria/value node}))
                           true)))

    (.forEach new-nodes (reify gnu.trove.TLongProcedure
                          (^boolean execute [_ ^long node]
                           (when-not (.contains old-nodes node)
                             (.add updates {:noria/update-type :add
                                            :noria/attr attr
                                            :noria/node parent-node
                                            :noria/value node
                                            :noria/index (vswap! i inc)}))
                           true)))
    updates))

(def ^:dynamic *updates* nil)
(def ^:dynamic *next-node* nil)
(def ^:dynamic *callbacks* nil)

(defn unordered? [new-exprs]
  (:noria/unordered? (meta new-exprs)))

(def node
 (with-meta
  (reify Reconciler
    (needsReconcile [this state new-arg]
      (not= [(::type state) (::attrs state)] new-arg))
    (reconcile [this frame state [type attrs]]
               (let [old-type (::type state)
                     old-node (::node state)
                     constructor-params (constructor-parameters type)
                     reduce-keys-of-two-maps (fn [r-f state m1 m2]
                                               (as-> state <>
                                                     (reduce-kv (fn [state a v]
                                                                  (r-f state a v (get m2 a)))
                                                                <> m1)
                                                     (reduce-kv (fn [state a v]
                                                                  (if (contains? m1 a)
                                                                    state
                                                                    (r-f state a nil v)))
                                                                <> m2)))
                     init-children (fn [new-value]
                                     (if (nil? new-value)
                                       nil
                                       (if (unordered? new-value)
                                         (transduce (keep (partial t/deref-or-value frame))
                                                    (completing
                                                     (fn [^TLongHashSet s ^long i]
                                                       (.add s i)
                                                       s))
                                                    (TLongHashSet.)
                                                    new-value)
                                         (transduce (keep (partial t/deref-or-value frame))
                                                    (completing
                                                     (fn [^TLongArrayList s ^long i]
                                                       (.add s i)
                                                       s))
                                                    (TLongArrayList.)
                                                    new-value))))
                     node-id (if (= old-type type)
                               old-node
                               (let [node-id (swap! *next-node* inc)]
                                 (when old-node
                                   (swap! *updates* conj! {:noria/update-type :destroy
                                                           :noria/node old-node}))
                                 node-id))
                     attrs'
                     (if (= old-node node-id)
                       (persistent!
                        (reduce-keys-of-two-maps
                         (fn [state attr old-value new-value]
                           (let [new-value (t/deref-or-value frame new-value)
                                 data-type (if (or (fn? new-value) (fn? old-value))
                                             :callback
                                             (data-type attr))]
                             (case data-type
                               (:node :simple-value)
                               (if (not= old-value new-value)
                                 (do
                                   (swap! *updates* conj! {:noria/update-type :set-attr
                                                           :noria/value new-value
                                                           :noria/node node-id
                                                           :noria/attr attr})
                                   (assoc! state attr new-value))
                                 state)

                               :callback
                               (do
                                 (swap! *callbacks* update node-id assoc (name attr) new-value)
                                 (cond
                                   (and (nil? old-value) (nil? new-value))
                                   state

                                   (and (nil? old-value) (some? new-value))
                                   (do
                                     (swap! *updates* conj! {:noria/update-type :set-callback
                                                             :noria/node node-id
                                                             :noria/attr attr
                                                             :noria/value (if (:noria/sync (meta new-value))
                                                                            :noria-handler-sync
                                                                            :noria-handler-async)})
                                     (assoc! state attr new-value))

                                   (and (some? old-value) (nil? new-value))
                                   (do
                                     (swap! *updates* conj! {:noria/update-type :set-callback
                                                             :noria/node node-id
                                                             :noria/attr attr
                                                             :noria/value nil})
                                     (dissoc! state attr))

                                   :else state))
                               :nodes-seq
                               (let [new-nodes (if (nil? new-value)
                                                 (if (nil? old-value)
                                                   nil
                                                   (if (unordered? old-value)
                                                     (TLongHashSet.)
                                                     (TLongArrayList.)))
                                                 (init-children new-value))
                                     old-nodes (or old-value (if (unordered? new-value) (TLongHashSet.) (TLongArrayList.)))
                                     updates (if (and (nil? new-value)
                                                      (nil? old-value))
                                               nil (if (unordered? new-value)
                                                     (update-set node-id attr old-nodes new-nodes)
                                                     (update-order node-id attr old-nodes new-nodes)))]
                                 (swap! *updates* (fn [u] (reduce conj! u updates)))
                                 (assoc! state attr new-nodes)))))
                         (transient (::attrs state))
                         (::attrs state)
                         attrs))
                       (let [[attrs' constr constr-cbs updates]
                             (reduce (fn [[attrs constr constr-cbs updates] [attr value]]
                                       (let [new-value (t/deref-or-value frame value)
                                             data-type (if (fn? new-value)
                                                         :callback
                                                         (data-type attr))]
                                         (case data-type
                                           (:node :simple-value)
                                           (if (contains? constructor-params attr)
                                             [(assoc! attrs attr new-value) (assoc! constr attr new-value) constr-cbs updates]
                                             [(assoc! attrs attr new-value)
                                              constr
                                              constr-cbs
                                              (conj! updates {:noria/update-type :set-attr
                                                              :noria/value new-value
                                                              :noria/node node-id
                                                              :noria/attr attr})])

                                           :callback
                                           (if (some? new-value)
                                             (do
                                               (swap! *callbacks* update node-id assoc (name attr) new-value)
                                               (let [cb (if (:noria/sync (meta new-value))
                                                          :noria-handler-sync
                                                          :noria-handler-async)]
                                                 (if (contains? constructor-params attr)
                                                   [(assoc! attrs attr cb) constr (assoc! constr-cbs attr cb) updates]
                                                   [(assoc! attrs attr cb)
                                                    constr
                                                    constr-cbs
                                                    (conj! updates {:noria/update-type :set-callback
                                                                    :noria/node node-id
                                                                    :noria/attr attr
                                                                    :noria/value cb})])))
                                             [attrs constr constr-cbs updates])
                                           :nodes-seq
                                           (let [new-nodes (init-children new-value)]
                                             (if (contains? constructor-params attr)
                                               [(assoc! attrs attr new-nodes)
                                                (assoc! constr attr new-nodes)
                                                constr-cbs
                                                updates]
                                               [(assoc! attrs attr new-nodes)
                                                constr
                                                constr-cbs
                                                (transduce
                                                 (comp
                                                  (keep (partial t/deref-or-value frame))
                                                  (map-indexed (fn [i e]
                                                                 {:noria/update-type :add
                                                                  :noria/node node-id
                                                                  :noria/attr attr
                                                                  :noria/value e
                                                                  :noria/index i})))
                                                 conj!
                                                 updates
                                                 new-value)])))))
                                     [(transient {}) (transient {}) (transient {}) (transient [])]
                                     attrs)]
                         (swap! *updates* (fn [u]
                                            (reduce conj!
                                                    (conj! u
                                                           {:noria/update-type :make-node
                                                            :noria/type type
                                                            :noria/node node-id
                                                            :noria/constructor-parameters (persistent! constr)
                                                            :noria/constructor-callbacks (persistent! constr-cbs)})
                                                    (persistent! updates))))
                         (persistent! attrs')))]
                 (t/result
                  {::attrs attrs'
                   ::type  type
                   ::node  node-id}
                  node-id
                  (not= old-node node-id))))
    (destroy [this {::keys [node attrs]}]
      (swap! *callbacks* dissoc node)
      #_(doseq [[attr value] attrs]
        (case (data-type attr)
          :node (swap! *updates* conj! {:noria/update-type :set-attr
                                        :noria/node node
                                        :noria/attr attr
                                        :noria/value nil})
          :nodes-seq (doseq [n value]
                      (swap! *updates* conj! {:noria/update-type :remove
                                              :noria/node node
                                              :noria/attr attr
                                              :noria/value n}))
          nil))
      (swap! *updates* conj! {:noria/update-type :destroy
                              :noria/node node})))
   {:noria/primitive true}))

(defn photon-node [type attrs children]
  (macros/-< node type (assoc (t/deref-or-value attrs)
                              :photon/children (t/deref-or-value children))))

(def set-attr!
  (with-meta
    (reify Reconciler
      (needsReconcile [this state new-arg]
        (not= [(:node state) (:attr state) (:value state)] new-arg))
      (reconcile [this frame state [node attr value]]
        (t/with-frame frame
          (let [node (t/deref-or-value node)
                value (t/deref-or-value value)]
            (when (and (some? (:node state))
                       (or (not= node (:node state))
                           (not= attr (:attr state))))
              (swap! *callbacks* update (:node state) dissoc (:state attr))
              #_(swap! *updates* conj! {:noria/update-type :set-attr
                                      :noria/value nil
                                      :noria/node (:node state)
                                      :noria/attr (:attr state)}))
            (when (or (not= value (:value state))
                      (not= node (:node state))
                      (not= attr (:attr state)))
              (when (= (data-type attr) :callback)
                (swap! *callbacks* update node assoc (name attr) value))
              (swap! *updates* conj! {:noria/update-type :set-attr
                                      :noria/value value
                                      :noria/node node
                                      :noria/attr attr}))
            (t/result
              (assoc state
                :node node
                :attr attr
                :value value)
              nil false))))
     (destroy [this state]
       (swap! *callbacks* update (:node state) dissoc (:attr state))
       #_(swap! *updates* conj! {:noria/update-type :set-attr
                               :noria/value nil
                               :noria/node (:node state)
                               :noria/attr (:attr state)})))
    {:noria/primitive true}))
