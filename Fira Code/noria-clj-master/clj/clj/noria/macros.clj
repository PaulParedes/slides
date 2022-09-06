(ns noria.macros
  (:require [noria.thunks :as t]
            [clojure.spec.alpha :as s])
  (:import [noria Thunk Reconciler Bindings]))

(s/def :noria/key any?)
(s/def :noria/bind any?)

(s/def ::key-spec (s/? (s/keys* :opt [:noria/key :noria/bind])))

(s/def ::-< (s/cat :key-spec ::key-spec
                   :thunk-def any?
                   :args (s/* any?)))
(s/fdef -< :args ::-<)
(defmacro -< [& stuff]
  (let [{{key :noria/key
          bind :noria/bind} :key-spec
         :keys [thunk-def args]} (s/conform ::-< stuff)
        key (or key `(quote ~(gensym)))]
    `(let [thunk# ~thunk-def
           ^noria.OpenFrame frame# (.get Thunk/FRAME)
           bindings# (.merge ^Bindings (.getBindings frame#) ~bind)]
       (if (instance? Reconciler thunk#)
         (t/reconcile* frame# ~key thunk# ~(first args) bindings#)
         (t/thunk* frame# ~key thunk# [~@args] bindings#)))))

(s/def ::-<expr (s/cat :key-spec ::key-spec
                       :expr (s/* any?)))
(s/fdef -<expr :args ::-<expr)
(defmacro -<expr [& stuff]
  (require '[clojure.tools.analyzer.jvm :as an-jvm])
  (let [{{key :noria/key
          bind :noria/bind :as key-spec} :key-spec
         :keys [expr]} (s/conform ::-<expr stuff)
        key (or key `(quote ~(gensym)))
        analyze (resolve 'clojure.tools.analyzer.jvm/analyze)
        empty-env (resolve 'clojure.tools.analyzer.jvm/empty-env)
        ast (analyze expr (assoc (empty-env)
                                 :locals (into {}
                                               (map (fn [[sym binding]]
                                                      [sym {:op :const,
                                                            :env (empty-env)
                                                            ::closure? (instance? clojure.lang.Compiler$LocalBinding binding)}]))
                                               &env)))
        nodes (tree-seq (comp seq :children)
                        (fn [node]
                          (mapcat (fn [x]
                                    (if (map? x)
                                      [x]
                                      x))
                                  ((apply juxt (:children node)) node)))
                        ast)
        env-map-expr (into []
                           (comp
                            (filter #(= (:op %) :local))
                            (filter (fn [{:keys [form env]}]
                                      (::closure? (get (:locals env) form))))
                            (map :form))
                           nodes)
        d (count key-spec)]
    `(let [^noria.OpenFrame frame# (.get Thunk/FRAME)
           bindings# (.merge ^Bindings (.getBindings frame#) ~bind)]
       (t/reconcile* frame# ~key t/closure [(fn [] ~@(drop (* 2 d) stuff)) ~env-map-expr] bindings#))))

(s/fdef -<state :args ::-<expr)
(defmacro -<state [& args]
  (let [{{key :noria/key
          bind :noria/bind} :key-spec
         :keys [expr]} (s/conform ::-<expr args)
        key (or key `(quote ~(gensym)))]
    `(let [supplier# (fn [] ~@expr)
           ^noria.OpenFrame frame# (.get Thunk/FRAME)
           bindings# (.merge ^Bindings (.getBindings frame#) ~bind)]
       (t/state* ~key frame# supplier# bindings#))))

(defmacro -<scope [& args]
  (let [{{key :noria/key} :key-spec
         :keys [expr]} (s/conform ::-<expr args)
        key (or key `(quote ~(gensym)))]
  `(let [^noria.OpenFrame frame# (.get Thunk/FRAME)]
    (.enterScope frame# ~key)
    (let [res# ~@expr]
      (.exitScope frame#)
      (noria.ValueThunk. res#)))))

(defmacro -<< [f & args]
  `(-<scope (~f ~@args)))

