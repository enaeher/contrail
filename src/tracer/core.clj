(ns tracer.core
  "The main namespace for Tracer."
  (:require [richelieu.core :as richelieu]
            [clojure.pprint :as pprint]))

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

(defn- get-predicate [when-fn arg-count]
  (apply every-pred `(~#(apply when-fn %)
                      ~@(when arg-count (list #(= arg-count (count %)))))))

(defn get-trace-report-fn [report-before-fn report-after-fn]
  (fn [f & args]
    (report-before-fn args)
    (let [retval (binding [*trace-level* (inc *trace-level*)]
                   (maybe-force-eager-evaluation (apply f args)))]
      (report-after-fn retval)
      retval)))

(defn- get-wrapped-fn [f when-fn report-before-fn report-after-fn arg-count]
  (let [predicate (get-predicate when-fn arg-count)
        trace-report-fn (get-trace-report-fn report-before-fn report-after-fn)]
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

  If `report-before-fn` is provided, it will be called before the
  traced function is called, with the same arguments as the traced
  function, and should print some useful output. It defaults to
  `tracer.core/report-before` if not provided.

  If `report-after-fn` is provided, it will be called after the traced
  function is called, with that function's return value as its
  argument, and should print some useful output. It defaults to
  `trace.core/report-after` if not provided.

  If `arg-count` (an integer) is provided, the trace reporting will
  only occur when the traced function is called with that number of
  arguments. (Note that this is not a true arity selector, since,
  there is no way to specify that only the variadic arity of a
  multi-arity function should be traced.)"
  [f & {:keys [when-fn report-before-fn report-after-fn arg-count]
        :or {when-fn (constantly true)
             report-before-fn report-before
             report-after-fn report-after}}]
  {:pre [(var? f)
         (fn? @f)]}
  (when (traced? f)
    (println f "already traced, untracing first.")
    (untrace f))
  (let [advice-fn (get-wrapped-fn f when-fn report-before-fn report-after-fn arg-count)]
    (richelieu/advise-var f advice-fn)
    (swap! traced-vars assoc f advice-fn)
    f))
