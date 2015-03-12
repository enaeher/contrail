(ns contrail.persistent-advice
  (:require [richelieu.core :as richelieu]))

(defonce ^:private advised-vars (atom '{}))

(defn all-advised []
  (keys @advised-vars))

(defn current-advised []
  richelieu/*current-advised*)

(def ^:private ^:dynamic *suppress-readvising?*
  "Used to prevent infinite recursion when re-advising a var after it
  has been re-defined by the user"
  false)

(defn unadvise [f]
  (binding [*suppress-readvising?* true]
    (remove-watch f "contrail")
    (richelieu/unadvise-var f (@advised-vars f))
    (swap! advised-vars dissoc f)))

(defn advise* [f advice-fn]
  (richelieu/advise-var f advice-fn)
  (swap! advised-vars assoc f advice-fn))

(defn advise [f wrapping-fn]
  (advise* f (wrapping-fn f))
  (add-watch f "contrail"
             (fn [_ advised-var _ new-var-value]
               (when-not *suppress-readvising?*
                 (binding [*suppress-readvising?* true]
                   (println f "changed, re-tracing.")
                   (advise* advised-var (wrapping-fn (richelieu/advised new-var-value))))))))

