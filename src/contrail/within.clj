(ns contrail.within)

(defn- match? [potentially-enclosing-fn stack-trace-element]
  (= (.getName (class potentially-enclosing-fn))
     (.getClassName stack-trace-element)))

(defn within?
  "Returns true if potentially-enclosing-var "
  [potentially-enclosing-fn]
  (some (partial match? potentially-enclosing-fn)
        (.. Thread currentThread getStackTrace)))
