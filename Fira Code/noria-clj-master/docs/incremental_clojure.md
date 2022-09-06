# Incremental computations

Imagine this function:

```clj
(defn f [x y z]
  (+ x (* y z)))
```

What happens when we call it? Let’s add some logging:

```clj
(defn + [a b] (println "[calc]" a "+" b "=" (clojure.core/+ a b)) (clojure.core/+ a b))
(defn * [a b] (println "[calc]" a "*" b "=" (clojure.core/* a b)) (clojure.core/* a b))
```

If we call it, we get what we expect:

```clj
user=> (f 1 2 3)
[calc] 2 * 3 = 6
[calc] 1 + 6 = 7
7
```

If we call it again, all calculations will be redone:

```clj
user=> (f 1 2 3)
[calc] 2 * 3 = 6
[calc] 1 + 6 = 7
7
```

What if `+` and `*` were actually quite expensive to compute? In that case we would want to cache them, if we can. Ideally, if we call `f` repeatedly, nothing should happen at all:

```clj
user=> (f 1 2 3)
7
```

If we change arguments, we expect that only parts that absolutely need re-evaluation will be called:

```clj
user=> (f 4 2 3)
[calc] 4 + 6 = 10
10
```

Notice that multiplication wasn’t called because the arguments didn’t change.

Another elimination should kick in if multiplication arguments change but the result stays the same (notice no addition):

```clj
user=> (f 4 3 2)
[calc] 3 * 2 = 6
10
```

## Adding incrementality

Noria does exactly that: given a deep call tree, it ensures that on subsequent runs only the absolutely necessary parts of computation will be re-calculated.

Let’s rewrite out function using Noria’s `-<` macro:

```clj
(require
  '[noria.thunks :as n]
  '[noria.macros :refer [-<]])

(defn g [x y z]
  (-< + x @(-< * y z)))
```

We’ll need a helper function that will remember the last state of the evaluation in a var.

```clj
(def dag nil)

(defn evaluate [fun & args]
  (let [[dag' value] (n/evaluate dag fun args)]
    (alter-var-root #'dag (constantly dag'))
    value))
```

Note that there’s no magic or any global state in the Noria itself. Both `dag` and `evaluate` here are just convenience functions for us to remember previous state of calculations in the REPL. In your program, feel free to store that in an atom, component, database, global var or any other way that best suits you.

Another important note is that all Noria macros (`-<`, `-<<`, thunks, derefs etc) will only work in a context of `n/evaluate`. E.g., if you try to call `g` directly:

```
user=> (g 1 2 3)
Execution error (NullPointerException) at noria.thunks/thunk* (thunks.clj:52).
null
```

Ok, let’s see if our caching works:

```clj
=> (evaluate g 1 2 3)
[calc] 2 * 3 = 6
[calc] 1 + 6 = 7
7
=> (evaluate g 1 2 3)
7
=> (evaluate g 4 2 3)
[calc] 4 + 6 = 10
10
=> (evaluate g 4 3 2)
[calc] 3 * 2 = 6
10
=> (evaluate g 4 3 2) 
10
```

As you can see multiplication is only called when `y` or `z` change, and addition is only called if `x` or the result of the multiplication is change.

## How does it work?

The magical `-<` macro that you’ve seen above does two things:

1. It calls your function and wraps its return value in a `Thunk`. As a user, all you need to know is that it is derefable, just like any other Clojure ref: `(f 1 2 3) === @(-< f 1 2 3)`.
2. It caches the returned Thunk by the call site. That means that on subsequent calculations, when execution reaches the same place in a code via the same call path, Noria will have access to the previous Thunk value. That gives Noria a chance to compare old and new arguments and decide whether computation should be re-run or not.

We can inspect the state of the call graph after evaluation happen:

```clj
user=> (evaluate g 1 2 3)
[calc] 2 * 3 = 6
[calc] 1 + 6 = 7
7
user=> (for [entry (.-values dag)
             :let [id    (.-key entry)
                   calc  (.-value entry)
                   sv    (.-stateAndValue calc)
                   value (.-value sv)]]
         {:id id, :value value, :args (.-arg calc)})
({:id 0, :value Thunk#2, :args (1 2 3)}
 {:id 1, :value ValueThunk[6], :args [2 3]}
 {:id 2, :value ValueThunk[7], :args [1 6]})
```

Thunk with id 0 corresponds to the top-level `g` call. Thunk #1 is the multiplication, and thunk #2 is an addition. As you can see Noria carefully stored all the arguments, return values and identities of each call.

The `-<` actually lets you hook into the process of re-evaluation. First argument to `-<` doesn’t have to be a function, but can be any object implementing `noria.Reconciler` interface. Noria already has implementation for Clojure functions via `n/adopt`:

```clj
(reify noria.Reconciler
  (needsReconcile [_ [args _] new-args]
    (not= args new-args))
  (reconcile [_ frame [args value] new-args]
    (with-frame frame
      (let [new-value (apply f new-args)]
        (result
          [new-args new-value]
          new-value
          (not= new-value value)))))
  (destroy [_ state]))
```

We can implement our own reconciler to better understand what’s going on:

```clj
(defrecord LoggingFnReconciler [op fun]
  noria.Reconciler
  (needsReconcile [_ [id old-args _] new-args]
    (let [reconcile? (not= old-args new-args)]
      (println (format "(needsReconcile %s %s %s) => %s ;; thunk %s" op old-args new-args reconcile? id))
      reconcile?))
  (reconcile [_ frame [_ _ old-value] args]
    (n/with-frame frame 
      (let [value      (apply fun args)
            propagate? (not= value old-value)]
        (println (format "(reconcile %s %s) => %s (propagate %s) ;; thunk %s" op args value propagate? (.id frame)))
        (n/result [(.id frame) args value] value propagate?))))
  (destroy [_ [id _ _]]
    (println (format "(destroy %s) ;; thunk %s" op id))))

(def r+ (LoggingFnReconciler. "+" clojure.core/+))
(def r* (LoggingFnReconciler. "*" clojure.core/*))

(defn h [x y z]
  (-< r+ x @(-< r* y z)))
```

Let’s try evaluating `h` and see what happens:

```clj
user=> (evaluate h 1 2 3)
(reconcile * [2 3]) => 6 (propagate true) ;; thunk 1
(reconcile + [1 6]) => 7 (propagate true) ;; thunk 2
7

user=> (evaluate h 1 3 2)
(needsReconcile * [2 3] [3 2]) => true ;; thunk 1
(reconcile * [3 2]) => 6 (propagate false) ;; thunk 1
(needsReconcile + [1 6] [1 6]) => false ;; thunk 2
7

user=> (evaluate h 4 3 2)
(needsReconcile * [3 2] [3 2]) => false ;; thunk 1
(needsReconcile + [1 6] [4 6]) => true ;; thunk 2
(reconcile + [4 6]) => 10 (propagate true) ;; thunk 2
10
```

Notice how Noria carefully checks if function arguments has changed (to see if it needs to call `reconcile`) and if result value has changed (to see if dependent calculations need to be propagated).

Let’s write one more function to see how Noria handles changes in a call graph (when different branches of the code gets evaluated based on conditions):

```clj
user=> (def r- (LoggingFnReconciler. "-" clojure.core/-))
#'user/r-

user=> (defn i [bool x y]
         (if bool
           (-< r- x y)
           (-< r- y x)))
#'user/i

user=> (evaluate i true 1 2)
(reconcile - [1 2]) => -1 (propagate true) ;; thunk 1
-1

user=> (evaluate i false 1 2)
(reconcile - [2 1]) => 1 (propagate true) ;; thunk 2
(destroy -) ;; thunk 1
1
```

You can see, even though we used the same reconciler for both branches, Noria actually distingueshed between two branches and created separate frames for each. It also garbage collected first frame after it was not reinstantiated on a second invocation (thus the `destroy` call).

## Stateful thunks

One use of Noria is just to cache expensive calculations. As long as your functions are pure and side-effect-free, default function reconciler would work just fine. But where’s fun in that?

Noria is actually pretty good at creating and managing stateful objects at the `-<` call sites. If you have a stateful tree that needs updating over time, implement convenient callbacks like add/update/remove nodes in place and let Noria figure out when and how to call them and store the intermediate state.

Imagine following reconciler:

```clj
(defrecord DOMNode [tagName]
  noria.Reconciler
  (needsReconcile [_ [old-args _] new-args]
    (not= old-args new-args))
  (reconcile [_ frame [old-args old-node] args]
    (if (some? old-node)
      ;; update
      (let [[attrs & children] args
            [old-attrs & old-children] old-args]
        ;; update old-attrs -> attrs
        ;; update old-children -> children
        (n/result [args old-node] old-node false)) ;; do not propagate — same node
      ;; create
      (let [new-node (js/document.createElement tagName)
            [attrs & children] args]
        (doseq [[k v] attrs]
          (.setAttribute new-node k v))
        (doseq [child children]
          (.appendChild new-node
            (cond
              (string? child) (js/Text. child)
              (instance? noria.Thunk child) (.read ^noria.Thunk child frame))]]))
        (n/result [args new-node] new-node true))))
  (destroy [_ [_ old-node]]
    (.remove old-node)))
```

Because Noria tracks where and when thunks appear/update/disappear, we can hook into reconciler lifecycle to do side effects we need to update the tree. The `DOMNode` reconciler above, combined with Noria’s `-<` macro, implement Virtual DOM paradigm. Yes, it’s that simple. This is how it can be used:


```clj
(def *clicks (atom 0))

(def div (DOMNode. "div"))
(def span (DOMNode. "span"))

(defn header [text]
  (-< span {"className" "header"} text))

(defn vdom-app []
  (-< div {"id" "app"}
    (header "Clicker app")
    (-< div {}
      (-< span {} "Clicks count" @*clicks))))

;; initial mount
(js/document.documentElement.appendChild (evaluate vdom-app))

;; redraw: only minimal necessary nodes update
(swap! *clicks inc)
(js/document.documentElement.appendChild (evaluate vdom-app))
```

Note: Noria is JVM-only at the moment, so code above would not compile under CLJS. It’s there to give you the idea of how Noria can be used.

## Bottom-up reevaluation

In the example above you might’ve noticed that full reevaluation from the top might be unnecessary if it’s only `*clicks` that changed. In the ideal world, we would want to specifically update the latest span and touch nothing more. We would prefer to not even go through `vdom-app` body at all!

In Noria, this is possible through the `:dirty-set` option to `n/evaluate`. If you track your components’ data dependencies yourself, you can figure out which thunks to update and Noria will take care of the rest. This is a bit trickier to set up, but it has a potential to be much more efficient in the real world.

```clj
(def *dirty (atom {}))

(def RefReconciler
  (reify noria.Reconciler
    (needsReconcile [_ [id old-args _] new-args]
      (let [reconcile? (= @(first old-args) @(first new-args))]
        (println (format "(needsReconcile ref) => %s ;; thunk %s" reconcile? id))
        reconcile?))
    (reconcile [_ frame [_ old-args old-value] args]
      (n/with-frame frame
        (let [[*ref] args
              id     (.id frame)
              value  @*ref]
          (println (format "(reconcile ref) => %s ;; thunk %s" value id))
          (add-watch *ref id (fn [_ _ old new] (swap! *dirty assoc id nil)))
          (n/result [id args value] value (not= value old-value)))))
    (destroy [_ [id args _]]
      (let [[*ref] args]
        (println (format "(destroy ref) ;; thunk %s" id))
        (remove-watch *ref id)))))

(defn evaluate-dirty [fun]
  (let [dirty  @*dirty
        _      (reset! *dirty {})
        result (n/evaluate dag fun [] {:dirty dirty})]
    (alter-var-root #'dag (constantly (.-graph result)))
    (.-rootValue result)))
```

Now define a “dirty” test function that does not take any arguments and reads its state from atom instead:

```clj
(def rstr (LoggingFnReconciler. "rstr"
            (fn [& args]
              (apply str (map deref args)))))

(def *a (atom 0))
(def *b (atom 5))
(def *c (atom 9))

(defn j []
  (-< rstr (-< RefReconciler *a) (-< RefReconciler *b) (-< RefReconciler *c)))
```

Let’s see how a thunks will behave when evaluation starts from the dirty set:

```clj
user=> (evaluate-dirty j)
(reconcile ref) => 0 ;; thunk 1
(reconcile ref) => 5 ;; thunk 2
(reconcile ref) => 9 ;; thunk 3
(reconcile str [Thunk#1 Thunk#2 Thunk#3]) => 059 (propagate true) ;; thunk 4
"059"

user=> (evaluate-dirty j)
"059"

user=> (swap! *b inc)
6

user=> @*dirty 
{2 nil}

user=> (evaluate-dirty j)
(reconcile ref) => 6 ;; thunk 2
(reconcile rstr [Thunk#1 Thunk#2 Thunk#3]) => 069 (propagate true) ;; frame 4
"069"
```

Note how `reconcile` starts directly from the deeps of second `RefReconciler` and then bubbles up all the way until the return value. In this particular case bubble all the way up, but it doesn’t always have to be the case. If all you do in reconciler is side effects (mutating a DOM tree for example), dirty set update might just update a single node deep down in the DOM tree and stop change propagation right there because DOM node _reference_ does not change. Of course this is optimized for mutable trees only, but hey, we’re trying to be fast here.