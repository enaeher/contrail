(ns contrail.core
  "The main namespace for Contrail."
  (:require [richelieu.core :as richelieu]
            [clojure.pprint :as pprint]
            [contrail.within :as within]))

(def ^:dynamic *trace-level*
  "Within a `report-before-fn` or `report-after-fn`, `*trace-level*`
  will be bound to the number of traced functions currently on the
  stack, excluding the function being reported upon."
  0)

(def ^:dynamic *trace-indent-per-level*
  "When using the default reporting functions, this var controls the
  number of spaces to indent per level of nested trace
  output. Defaults to 2."
  2)

(def ^:dynamic *force-eager-evaluation*
  "When true (the default), return values of traced functions are
  always realized immediately to ensure that trace output is printed
  in logical order and that `*trace-level*` is always bound to the
  logically-correct value."
  true)

(defn- trace-indent
  "Returns the number of spaces to indent at the current `*trace-level*`."
  []
  (* *trace-indent-per-level* *trace-level*))

(defonce ^:private traced-vars (atom '{}))

(defn all-traced
  "Returns a sequence of all currently-traced vars."
  []
  (map first @traced-vars))

(defn traced?
  "Returns true if the function bound to var `f` is traced, otherwise
  returns false."
  [f]
  (boolean (some #{f} (keys @traced-vars))))

(defn untrace
  "When called with no arguments, untraces all traced functions. When
  passed a var `f`, untraces the function bound to that var."
  ([]
   (doseq [[f _] @traced-vars]
     (untrace f)))
  ([f]
   (richelieu/unadvise-var f (@traced-vars f))
   (println "Untracing" f)
   (swap! traced-vars dissoc f)))

(defn untrace-ns
  "Untraces every traced var in the namespace `ns`"
  [ns]
  (doseq [[traced-fn _] (filter #(= (:ns (meta (first %))) ns) @traced-vars)]
    (untrace traced-fn)))

(defn report-before
  "Prints a nicely-formatted list of the currently-traced function
  with its `args`, indented based on the current `*trace-level*`"
  [args]
  (pprint/cl-format true "~&~vt~d: (~s ~{~s~^ ~})~%" (trace-indent) *trace-level* richelieu/*current-advised* args))

(defn report-after
  "Prints a nicely-formatted list of the currently-traced function
  with its `retval`, indented based on the current `*trace-level*`."
  [retval]
  (pprint/cl-format true "~&~vt~d: ~s returned ~s~%" (trace-indent) *trace-level* richelieu/*current-advised* retval))

(defn- maybe-force-eager-evaluation [thunk]
  (if (and *force-eager-evaluation* (seq? thunk))
    (doall thunk)
    thunk))

(defn- get-predicate
  "Returns a function which, when called with the traced function's
  argument list (as a sequence), determines whether or not to run
  trace reporting for this invocation of the traced function."
  [when-fn arg-count within]
  (apply every-pred `(~#(apply when-fn %)
                      ~@(when within (list (fn [_] (within/within? @within))))
                      ~@(when arg-count (list #(= arg-count (count %)))))))

(defn get-trace-report-fn
  "Returns a function which unconditionally wraps `f` with trace
  reporting."
  [report-before-fn report-after-fn]
  (fn [f & args]
    (report-before-fn args)
    (let [retval (binding [*trace-level* (inc *trace-level*)]
                   (maybe-force-eager-evaluation (apply f args)))]
      (report-after-fn retval)
      retval)))

(defn- wrap-with-counter
  "Wraps `f`, which must be a trace reporting function whose first
  argument is the traced function, such that, after it is run `limit`
  times, the traced function is called directly and `f` is no longer
  called. Additionally sets up a watch such that, after `f` is called
  `limit` times, the traced var is untraced."
  [f limit]
  (let [remaining-count (atom limit)]
    (add-watch remaining-count (gensym)
               (fn [key _ _ new-value]
                 (when (zero? new-value)
                   (untrace richelieu/*current-advised*)
                   (remove-watch remaining-count key))))
    (fn [traced-f & args]
      ;; Necessary to avoid a race condition where we've called
      ;; untrace, which alters the var root to remove the trace, but
      ;; an unrealized sequence still maintains a reference to the
      ;; traced function.
      (if (zero? @remaining-count)
        (apply traced-f args)
        (let [retval (apply f traced-f args)]
          (swap! remaining-count dec)
          retval)))))

(defn- maybe-wrap-with-counter [f limit]
  (if limit
    (wrap-with-counter f limit)
    f))

(defn- get-wrapped-fn
  "Returns a function wrapping `f` with trace reporting as specified
  by the remaining arguments, and which is suitable for passing to
  `richelieu/advise-var`."
  [f when-fn within limit report-before-fn report-after-fn arg-count]
  (let [predicate (get-predicate when-fn arg-count within)
        trace-report-fn (get-trace-report-fn report-before-fn report-after-fn)
        trace-report-fn (maybe-wrap-with-counter trace-report-fn limit)]
    (fn [f & args]
      (if (predicate args)
        (apply trace-report-fn f args)
        (apply f args)))))

(defn trace
  "Turns on tracing for the function bound to `f`, a var. If `f` is
  already traced, trace will untrace it (warning the user), then
  re-enable tracing using the specified options.

  If `when-fn` is provided, the trace reporting described below will
  only occur when `when-fn` returns a truthy value when called with
  the same args as the traced functions. If `when-fn` is not provided,
  every call to the traced function will be reported.

  If `within` (a var bound to a function) is provided, the trace
  reporting will only occur when the traced function is called while
  the specified `within` function is on the stack.

  If `arg-count` (an integer) is provided, the trace reporting will
  only occur when the traced function is called with that number of
  arguments. (Note that this is not a true arity selector, since,
  there is no way to specify that only the variadic arity of a
  multi-arity function should be traced.)

  If `limit` (an integer) is provided, the trace reporting will only
  occur `limit` number of times, after which `untrace` will be called
  on the traced var. (If `limit` is specified in combination with
  `when-fn`, `within`, or `arg-count`, only calls to the traced
  function which actually meet those conditions and result in trace
  reporting will count toward the limit.)

  If `report-before-fn` is provided, it will be called before the
  traced function is called, with the same arguments as the traced
  function, and should print some useful output. It defaults to
  `contrail.core/report-before` if not provided.

  If `report-after-fn` is provided, it will be called after the traced
  function is called, with that function's return value as its
  argument, and should print some useful output. It defaults to
  `trace.core/report-after` if not provided."
  [f & {:keys [when-fn within arg-count limit report-before-fn report-after-fn]
        :or {when-fn (constantly true)
             report-before-fn report-before
             report-after-fn report-after}}]
  {:pre [(var? f)
         (fn? @f)
         (if within
           (and (var? within)
                (fn? @within))
           true)]}
  (when (traced? f)
    (println f "already traced, untracing first.")
    (untrace f))
  (let [advice-fn (get-wrapped-fn f when-fn within limit report-before-fn report-after-fn arg-count)]
    (richelieu/advise-var f advice-fn)
    (swap! traced-vars assoc f advice-fn)
    f))
