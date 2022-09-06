(ns noria.thunks-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is are use-fixtures]]
            [noria :refer [-< -<expr]])
  (:import [noria Reconciler]))

(def ^:dynamic *log)
(def ^:dynamic *evaluator)
(def ^:dynamic *dirty nil)

(defn wrap [f]
  (binding [*evaluator (atom nil)
            *dirty (atom {})]
    (f)))

(use-fixtures :each wrap)

(defn log [& args]
  (swap! *log conj (str/join " " args)))

(defn add [a b] (log "[add]" a "+" b "=" (+ a b)) (+ a b))
(defn mult [a b] (log "[mult]" a "*" b "=" (* a b)) (* a b))
(defn subtract [a b] (log "[subtract]" a "-" b "=" (- a b)) (- a b))

(defn evaluate [f & args]
  (binding [*log (atom [])]
    (let [evaluator (or @*evaluator (noria/noria f))
          [evaluator value] (evaluator args {:dirty-map (some-> *dirty deref)})]
      (reset! *evaluator evaluator)
      [@*log value])))

(deftest test-nesting
  (let [f (fn [x y z]
            (-< add x @(-< mult y z)))]
    (is (= [["[mult] 2 * 3 = 6" "[add] 1 + 6 = 7"] 7] 
          (evaluate f 1 2 3)))
    (is (= [[] 7]
          (evaluate f 1 2 3)))
    (is (= [["[add] 4 + 6 = 10"] 10]
          (evaluate f 4 2 3)))
    (is (= [["[mult] 3 * 2 = 6"] 10]
          (evaluate f 4 3 2)))))

(deftest test-branching
  (let [f (fn [reverse? a b]
            (if reverse?
              (-< subtract b a)
              (-< subtract a b)))]
    (is (= [["[subtract] 1 - 2 = -1"] -1] 
          (evaluate f false 1 2)))
    (is (= [["[subtract] 2 - 1 = 1"] 1] 
          (evaluate f true 1 2)))))

(def RefReconciler
  (reify noria.Reconciler
    (needsReconcile [_ bindings frame-id [id old-ref _] new-ref]
      (let [reconcile? (not= @old-ref @new-ref)]
        (log (format "(needsReconcile ref) => %s ;; thunk %s" reconcile? id))
        reconcile?))
    (reconcile [_ frame [_ old-args old-value] *ref]
      (noria/with-frame frame
        (let [id     (.id frame)
              value  @*ref]
          (log (format "(reconcile ref) => %s ;; thunk %s" value id))
          (add-watch *ref id (fn [_ _ old new] (swap! *dirty assoc id nil)))
          (noria/result [id *ref value] value (not= value old-value)))))
    (destroy [_ bindings frame-id [id *ref _]]
      (log (format "(destroy ref) ;; thunk %s" id))
             (remove-watch *ref id))))

(deftest test-dirty
  (let [*a (atom 0)
        *b (atom 5)
        *c (atom 9)
        rstr (fn [& args]
               (let [val (str/join "" (map deref args))]
                 (log (format "(reconcile rstr) => %s" val))
                 val))
        f (fn []
            (-< rstr (-< RefReconciler *a) (-< RefReconciler *b) (-< RefReconciler *c)))]
    (is (= [["(reconcile ref) => 0 ;; thunk 1"
             "(reconcile ref) => 5 ;; thunk 2"
             "(reconcile ref) => 9 ;; thunk 3"
             "(reconcile rstr) => 059"]
            "059"]
           (evaluate f)))
    (is (= {} @*dirty))

    (swap! *b inc)
    (is (= {2 nil} @*dirty))
    (is (= [["(reconcile ref) => 6 ;; thunk 2"
             "(reconcile rstr) => 069"]
            "069"]
           (evaluate f)))

    (reset! *b @*b)
    (is (= [["(reconcile ref) => 6 ;; thunk 2"] ;; no propagation
            "069"]
           (evaluate f)))))


(deftest test-dependants
  (let [*a (atom 0)
        f (fn []
            (log "root")
            (+ 1 @(-< RefReconciler *a)))]
    (is (= [["root"
             "(reconcile ref) => 0 ;; thunk 1"]
            1]
           (evaluate f)))
    (reset! *a 2)
    (is (= [["(reconcile ref) => 2 ;; thunk 1"
             "root"]
            3]
           (evaluate f)))))

(deftest test-pumping
  "Your child may have dirty descendant which will not be reached because child himself reports needReconcile false"
  (let [*a (atom 0)
        *b (atom 0)
        f (fn []
            (log "root")
            (+ @(-< RefReconciler *a) @(-<expr (-< RefReconciler *b))))]
    (is (= [["root"
             "(reconcile ref) => 0 ;; thunk 1"
             "(reconcile ref) => 0 ;; thunk 3"]
            0]
           (evaluate f)))
    (reset! *a 2)
    (reset! *b 3)
    (is (= [["(reconcile ref) => 2 ;; thunk 1"
             "root"
             "(reconcile ref) => 3 ;; thunk 3"]
            5]
           (evaluate f)))))

(deftest test-cousin-dependency
  (let [*a (atom 10)
        f (fn [x]
            (log "start root")
            (let [t (-<expr
                     (log "t")
                     (-< RefReconciler *a))]
              (-<expr (log "dependant" x @t))
              (log "end root")
              nil))]
    (evaluate f 1)
    (reset! *a 20)
    (is (= [["start root"
             "(reconcile ref) => 20 ;; thunk 2"
             "dependant 2 20"
             "end root"]
            nil]
           (evaluate f 2)))))

(deftest test-zombie-relatives
  "Pumping dirty descendants may lead to accidental revival of your dead child"
  (let [*a (atom 0)
        *b (atom 0)
        alive? (atom true)
        f (fn []
            (log "start root")
            (when @(-< RefReconciler alive?)
              (-< RefReconciler *a))
            (let [b @(-< RefReconciler *b)]
              (log "end root")
              b))]
    (is (= [["start root"
             "(reconcile ref) => true ;; thunk 1"
             "(reconcile ref) => 0 ;; thunk 2"
             "(reconcile ref) => 0 ;; thunk 3"
             "end root"] 0]
           (evaluate f)))
    (reset! alive? false)
    (reset! *a 2)
    (reset! *b 3)
    (is (= [["(reconcile ref) => false ;; thunk 1"
             "start root"
             "(reconcile ref) => 3 ;; thunk 3"
             "end root"
             "(destroy ref) ;; thunk 2"]
            3]
           (evaluate f)))))

(deftest reconsider-current-frame-for-reconciliation-after-pumping
  "Even if thunk opted out of reconciliation via needReconcile it can become 'dirty' after it's children was pumped and should be reconciled before proceeding"
  (let [*a (atom 0)
        alive? (atom true)
        f (fn []
            (log "start root")
            (let [<a> (-< RefReconciler *a)
                  <b> (-<expr
                       (log "child")
                       @(-< RefReconciler *a))]
              (log "end root")
              (+ @<a> @<b>)))]
    (evaluate f)
    (reset! alive? false)
    (reset! *a 2)
    (is (= [["(reconcile ref) => 2 ;; thunk 1"
             "start root"
             "(reconcile ref) => 2 ;; thunk 3"
             "child"
             "end root"]
            4]
           (evaluate f)))))

(deftest test-triggers
  (let [node (fn [child]
               (log (.id (noria/frame)) "node" child @child)
               (str "<node " @child ">"))
        vbox (fn [child]
               (log (.id (noria/frame)) "vbox" child)
               (str "<vbox " (-< node child) ">"))
        header (fn [id]
                 (log (.id (noria/frame)) "header" id)
                 (str "<header " id ">"))
        buffer (fn [id]
                 (log (.id (noria/frame)) "buffer" id)
                 (-< vbox (-< header id)))]
    (is (= [["0 buffer A" "1 header A" "2 vbox Thunk#1" "3 node Thunk#1 <header A>"] "<vbox Thunk#3>"]
           (evaluate buffer "A")))
    (is (= [["0 buffer B" "1 header B" "3 node Thunk#1 <header B>"] "<vbox Thunk#3>"] ;; vbox not triggered, but node is
           (evaluate buffer "B")))))

(deftest adopt-does-not-mess-with-arities
  (let [arity-fn (fn
                   ([x] x)
                   ([x y] (+ x y)))
        f (fn []
            [@(-< arity-fn 1)
             @(-< arity-fn 1 1)])]
    (is (= [[] [1 2]] (evaluate f)))))
