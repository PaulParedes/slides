(ns noria
  (:require [noria.thunks :as t])
  (:refer-clojure :exclude [with-bindings])
  (:import [gnu.trove TLongHashSet TLongArrayList TObjectLongHashMap]
           [java.io Writer]
           [java.util HashMap]
           [noria OpenFrame SealedFrame Reconciler Thunk Noria Bindings]
           [clojure.lang IFn]))

(defn ^OpenFrame frame []
  (.get Thunk/FRAME))

(defn frame-id [] (.id (frame)))

(defn thunk-id [^noria.Computation t]
  (.getThunkId t))

(defn get-binding
  ([k]
   (get-binding (.get Thunk/FRAME) k))
  ([^SealedFrame frame k]
   (.get (.getBindings frame) k)))

(defmacro with-frame [& args] `(t/with-frame ~@args))

(defn deref-or-value
  ([x]
   (deref-or-value (frame) x))
  ([frame x]
   (if (instance? Thunk x)
     (.read ^Thunk x frame)
     x)))

#_(defn runtime ^noria.Runtime [] noria.NoriaRT/INSTANCE)
(defn runtime ^noria.Runtime [] noria.NoriaImpl/Companion)

(defn noria
  ([f]
   (noria f {}))
  ([f {:noria/keys [middleware] :as root-bindings}]
   (fn [args & [{:keys []}]]
     (let [adopt-cache (HashMap.)
           reconciler  (t/adopt f adopt-cache middleware)
           result      (.evaluate (runtime)
                        reconciler
                        args
                        (Bindings/fromMap ^java.util.Map (merge
                                                          root-bindings
                                                          {:noria/adopt-cache adopt-cache}))
                        (reify kotlin.jvm.functions.Function1
                              (invoke [this arg] arg)))
           continue (fn continue [^Noria graph]
                      (with-meta
                        (fn revaluate
                          ([args]
                           (revaluate args nil))
                          ([args {:keys [dirty-map]}]
                           (let [result (.revaluate graph args (or dirty-map {}))]
                             [(continue result) (.getResult result)])))
                        {:noria/graph graph}))]
       [(continue result) (.getResult result)]))))

(defn graph ^Noria [noria]
  (:noria/graph (meta noria)))

(defn destroy [noria]
  (.destroy (graph noria)))

(defn flat [coll]
  (into []
        (fn flat-xf [r-f]
          (fn
            ([] (r-f))
            ([s i]
             (let [i (deref-or-value i)]
               (if (sequential? i)
                 (transduce flat-xf r-f s i)
                 (r-f s i))))
            ([s] (r-f s))))
        (deref-or-value coll)))

(defn fmap [f & args]
  (apply f (map deref-or-value args)))

(def once
  (reify Reconciler
    (needsReconcile [this frame arg] false)
    (reconcile [this frame f]
      (when-not (.getState frame)
        (with-frame frame
          (let [f (noria/deref-or-value frame f)
                res (f)]
            (.setValue frame res)))))
    (destroy [this frame])))

(def on-destroy
  (reify Reconciler
    (needsReconcile [this frame arg] true)
    (reconcile [this frame f] (.setState frame f))
    (destroy [this frame] (let [f (.getState frame)] (f)))))

(defmacro compile-time-require [& args]
  (apply require args)
  nil)

(compile-time-require noria.macros)

(defmacro -< [& args]
  `(noria.macros/-< ~@args))

(defmacro -<state [& args]
  `(noria.macros/-<state ~@args))

(defmacro -<expr [& args]
  `(noria.macros/-<expr ~@args))

(defmacro gen-id []
  `(thunk-id (-<expr)))

(defmacro -<scope [& args]
  `(noria.macros/-<scope ~@args))

(defmacro -<< [& args]
  `(noria.macros/-<< ~@args))

