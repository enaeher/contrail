(ns contrail.persistent-advice
  (:require [richelieu.core :as richelieu]))

(defonce ^:private advised-vars (atom #{}))

(defn all-advised []
  @advised-vars)

(defn current-advised []
  richelieu/*current-advised*)

(def ^:private ^:dynamic *suppress-readvising?*
  "Used to prevent infinite recursion when re-advising a var after it
  has been re-defined by the user"
  false)

(defn get-our-advising-fn
  "Of the various Richelieu advising functions which may advice `f`, a
  var, returns the first (and hopefully only) one created by
  Contrail."
  [f]
  ;; The deref is necessary because Richeleu actually sets the advice
  ;; on the function, not the var
  (first (filter (comp :traced-by-contrail meta) (richelieu/advice (deref f)))))

(defn unadvise [f]
  (binding [*suppress-readvising?* true]
    (remove-watch f "contrail")
    (richelieu/unadvise-var f (get-our-advising-fn f))
    (swap! advised-vars disj f)))

(defn advise* [f advice-fn]
  (richelieu/advise-var f advice-fn)
  (swap! advised-vars conj f))

(defn get-watcher [wrapping-fn]
  (fn [_ advised-var _ new-var-value]
    (when-not *suppress-readvising?*
      (binding [*suppress-readvising?* true]
        (println advised-var "changed, re-tracing.")
        (advise* advised-var (wrapping-fn (richelieu/advised new-var-value)))))))

(defn advise [f wrapping-fn]
  (letfn [(meta-wrapping-fn [f]
            (with-meta (wrapping-fn f) {:traced-by-contrail true}))]
    (advise* f (meta-wrapping-fn f))
    (add-watch f "contrail" (get-watcher meta-wrapping-fn))))

