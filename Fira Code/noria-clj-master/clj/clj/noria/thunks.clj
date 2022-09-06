(ns ^:lean-ns noria.thunks
  (:import [java.io Writer]
           [java.util HashMap]
           [noria SealedFrame OpenFrame Reconciler Thunk Computation]
           [clojure.lang IFn]))

(defmacro with-frame [frame & body]
  `(let [old-frame# (.get Thunk/FRAME)]
     (.set Thunk/FRAME ~frame)
     (let [res# (do ~@body)]
       (if (some? old-frame#)
         (.set Thunk/FRAME old-frame#)
         (.remove Thunk/FRAME))
       res#)))

(defn applyv [f args]
  (if (instance? clojure.lang.IPersistentVector args)
    (let [av ^clojure.lang.IPersistentVector args]
      (case (.length av)
        0 (f)
        1 (f (.nth av 0))
        2 (f (.nth av 0) (.nth av 1))
        3 (f (.nth av 0) (.nth av 1) (.nth av 2))
        4 (f (.nth av 0) (.nth av 1) (.nth av 2) (.nth av 3))
        5 (f (.nth av 0) (.nth av 1) (.nth av 2) (.nth av 3) (.nth av 4))
        6 (f (.nth av 0) (.nth av 1) (.nth av 2) (.nth av 3) (.nth av 4) (.nth av 5))
        (apply f av)))
    (apply f args)))

#_(defn result [state value propagate?]
  (noria.Reconciler$Result. state
    (if (instance? Thunk value)
      value
      (Thunk/of value))
    propagate?))

(defrecord fn-state [args value])

(defn adopt-fn [f]
  (with-meta
    (reify Reconciler
      (needsReconcile [_ frame new-args]
        (let [old-args (.getState frame)]
          (not= old-args new-args)))
      (reconcile [_ frame new-args]
        (let [value (.getValue frame)]
          (with-frame frame
            (let [value' (applyv f new-args)]
              (when (not= value value')
                (.propagate frame))
              (.setValue frame value')))
          (.setState frame new-args)))
      (destroy [_ frame]))
    (meta f)))

(defn adopt [something ^java.util.Map adopt-cache middleware]
  (or
    (.get adopt-cache something)
    (let [something' (cond
                       (instance? Reconciler something) something
                       (instance? IFn something) (adopt-fn something)
                       :else (throw (IllegalArgumentException. (str "Expected IFn, got " (type something)))))
          something'' (if (some? middleware) (middleware something') something')]
      (.put adopt-cache something something'')
      something'')))

(defn reconcile* [^OpenFrame frame key reconciler arg ^noria.Bindings bindings]
  (assert (instance? Reconciler reconciler))
  (let [adopt-cache (.get bindings :noria/adopt-cache)
        middleware (.get bindings :noria/middleware)
        reconciler' (adopt reconciler adopt-cache middleware)]
    (.child frame key reconciler' arg bindings)))

(defn thunk* [^OpenFrame frame key reconciler args-vector ^noria.Bindings bindings]
  (let [adopt-cache (.get bindings :noria/adopt-cache)
        middleware (.get bindings :noria/middleware)
        reconciler' (adopt reconciler adopt-cache middleware)]
    (.child frame key reconciler' args-vector bindings)))

(defn wrap-updater [^java.util.function.BiConsumer updater ^long thunk-id]
  (fn [transform-fn & args]
    (.accept updater thunk-id (reify java.util.function.Function
                                (apply [this state]
                                       ;;TODO this boxing is specific to state reconciler
                                  (noria.Box. (clojure.core/apply transform-fn (.getV ^noria.Box state) args)))))))

(defn state* [key ^OpenFrame frame supplier ^noria.Bindings bindings]
  (let [updater (.get bindings noria.APIKt/UPDATER_KEY)
        <state-thunk> (reconcile* frame
                                  key
                                  (noria.StateKt/getTheStateReconciler)
                                  (reify noria.StateArg
                                    (init [_]
                                      (supplier))
                                    (destroy [_ x]
                                      ))
                                  bindings)
        thunk-id (.getThunkId ^Computation <state-thunk>)]
    [<state-thunk> (wrap-updater updater thunk-id)]))

(defmethod print-method noria.Thunk [o, ^java.io.Writer w]
  (.write w (str o)))

(def closure
  (reify noria.Reconciler
    (needsReconcile [_ frame [f new-env]]
      (let [env (.getState frame)]
        (not= env new-env)))
    (reconcile [_ frame [f new-env]]
      (let [old-val (.getValue frame)
            new-val (with-frame frame (f))]
        (.setState frame new-env)
        (when (not= old-val new-val)
          (.propagate frame)
          (.setValue frame new-val))))
    (destroy [_ frame])))
