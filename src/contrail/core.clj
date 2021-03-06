(ns contrail.core
  "The main namespace for Contrail."
  (:require [clojure.pprint :as pprint]
            [contrail.within :as within]
            [contrail.persistent-advice :as advice]
            [richelieu.core :as richelieu]))

(def ^:dynamic *trace-out*
  "Trace output will be sent to this writer, if bound, otherwise to
  `*out*`"
  nil)

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
  "When true (the default), the default trace reporting will
  realize (and print) all arguments to traced functions, and return
  values of traced functions will be realized immediately to ensure
  that trace output is printed in logical order and that
  `*trace-level*` is always bound to the logically-correct value.

  When false, unrealized arguments and return values are not realized
  by the default trace reporting (and their contents are not printed)
  and return values are not forcibly realized."
  true)

(defn- get-trace-out []
  (or *trace-out* *out*))

(defn- trace-indent
  "Returns the number of spaces to indent at the current `*trace-level*`."
  []
  (* *trace-indent-per-level* *trace-level*))

(defn all-traced
  "Returns a sequence of all currently-traced vars."
  []
  (advice/all-advised))

(defn traced?
  "Returns true if the function bound to var `f` is traced, otherwise
  returns false."
  [f]
  (boolean (some #{f} (all-traced))))

(defn- untrace* [f]
  (advice/unadvise f))

(defn untrace
  "When called with no arguments, untraces all traced functions. When
  passed a var `f`, untraces the function bound to that var."
  ([]
   (doseq [f (all-traced)]
     (untrace f)))
  ([f]
   (println "Untracing" f)
   (untrace* f)))

(defn current-traced-var []
  (advice/current-advised))

(defn- get-string
  "Equivalent to `pr-str` except in the case that `thing` is an
  unrealized lazy sequence and `*force-eager-evaluation*` is not true,
  then returns an unreadable string representation which doesn't
  realize the sequence. (Note that `str` cannot be used here, as it
  realizes the sequence.)"
  [thing]
  (if (or (not (instance? clojure.lang.IPending thing))
          *force-eager-evaluation*
          (realized? thing))
    (pr-str thing)
    (str "#<" (.getName (class thing)) ">")))

(defn report-arguments [& args]
  (pprint/cl-format nil "(~s~@[ ~{~a~^ ~}~])" (current-traced-var) (seq (map get-string args))))

(defn report-before
  "Prints a nicely-formatted list of the currently-traced function
  with its `args`, indented based on the current `*trace-level*`"
  [args report-before-fn]
  (.write (get-trace-out)
          (pprint/cl-format nil "~&~vt~d: ~a~%"
                            (trace-indent) *trace-level* (apply report-before-fn args))))

(defn report-retval [retval]
  (pprint/cl-format nil "~s returned ~a" (current-traced-var) (get-string retval)))

(defn report-after
  "Prints a nicely-formatted list of the currently-traced function
  with its `retval`, indented based on the current `*trace-level*`."
  [retval report-after-fn]
  (.write (get-trace-out)
          (pprint/cl-format nil "~&~vt~d: ~a~%"
                            (trace-indent) *trace-level* (report-after-fn retval))))

(defn- maybe-force-eager-evaluation [thing]
  (if (and *force-eager-evaluation* (seq? thing))
    (doall thing)
    thing))

(defn- get-predicate
  "Returns a function which, when called with the traced function's
  argument list (as a sequence), determines whether or not to run
  trace reporting for this invocation of the traced function."
  [when-fn arity within]
  (apply every-pred `(~#(apply when-fn %)
                      ~@(when within (list (fn [_] (within/within? @within))))
                      ~@(when arity (list #(= arity (count %)))))))

(defn get-trace-report-fn
  "Returns a function which unconditionally wraps `f` with trace
  reporting."
  [report-before-fn report-after-fn]
  (fn [f & args]
    (report-before args report-before-fn)
    (let [retval (binding [*trace-level* (inc *trace-level*)]
                   (maybe-force-eager-evaluation (apply f args)))]
      (report-after retval report-after-fn)
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
                   (untrace (current-traced-var))
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

(defn- get-advice-fn
  "Returns a function wrapping its argument `f` with trace reporting
  as specified by the remaining arguments to `get-advice-fn`, and
  which is suitable for passing to `richelieu/advise-var`."
  [when-fn within limit report-before-fn report-after-fn arity]
  (let [predicate (get-predicate when-fn arity within)
        trace-report-fn (get-trace-report-fn report-before-fn report-after-fn)
        trace-report-fn (maybe-wrap-with-counter trace-report-fn limit)]
    (fn [f & args]
      (if (predicate args)
        (apply trace-report-fn f args)
        (apply f args)))))

(richelieu/defadvice trace
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

  If `arity` (an integer) is provided, the trace reporting will
  only occur when the traced function is called with that number of
  arguments. (Note that this is not a true arity selector, since,
  there is no way to specify that only the variadic arity of a
  multi-arity function should be traced.)

  If `limit` (an integer) is provided, the trace reporting will only
  occur `limit` number of times, after which `untrace` will be called
  on the traced var. (If `limit` is specified in combination with
  `when-fn`, `within`, or `arity`, only calls to the traced
  function which actually meet those conditions and result in trace
  reporting will count toward the limit.)

  If `report-before-fn` is provided, it will be called before the
  traced function is called, with the same arguments as the traced
  function, and should return a string to use as output. It defaults
  to `contrail.core/report-arguments` if not provided.

  If `report-after-fn` is provided, it will be called after the traced
  function is called, with that function's return value as its
  argument, and should return a string to use as output. It defaults
  to `trace.core/report-retval` if not provided."
  [f & {:keys [when-fn within arity limit report-before-fn report-after-fn]
        :or {when-fn (constantly true)
             report-before-fn report-arguments
             report-after-fn report-retval}}]
  {:pre [(var? f)
         (or (fn? @f)
             (instance? clojure.lang.MultiFn @f))
         (if within
           (and (var? within)
                (fn? @within))
           true)]}
  (when (traced? f)
    (println f "already traced, untracing first.")
    (untrace f))
  (advice/advise f (get-advice-fn when-fn within limit report-before-fn report-after-fn arity))
  f)
