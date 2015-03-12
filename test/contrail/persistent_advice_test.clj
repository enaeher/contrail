(ns contrail.persistent-advice-test
  (:require [contrail.persistent-advice :refer :all]
            [clojure.test :refer :all]))

(use-fixtures :each
  (fn [f]
    (defn foo "An example function used to test persistent advice" [])
    (unadvise #'foo)
    (f)))

(defn sample-advice-fn
  "An example wrapping function used to test persistent advice"
  [f & args]
  (apply f args))

(deftest advice-survives-redef
  (testing "Advice fn survives redef"
    (advise #'foo sample-advice-fn)
    (defn foo [])
    (foo)
    (is (get-our-advising-fn #'foo) "Advising fn didn't survive redef")))

(deftest unadvising-after-redef
  (testing "Unadvising should work after a redef"
    (advise #'foo sample-advice-fn)
    (defn foo [])
    (foo)
    (unadvise #'foo)
    (is (not (get-our-advising-fn #'foo)) "Advising fn shouldn't have survived unadvise")))

(deftest with-redef-doesnt-re-advice
  (testing "If a var is unadvised within a redef, it should continue to be unadvised after the with-redef"
    (let [was-run? (atom false)]
      (advise #'foo (fn [& _] (swap! was-run? not)))
      (with-redefs [foo (fn [])]
        (unadvise #'foo))
      (is (get-our-advising-fn #'foo) "The advising function will still be there when the redef exits")
      (foo)
      (is (not (get-our-advising-fn #'foo)) "We should remove the advising fn when it tries to run")
      (is (not @was-run?) "The advising fn should never execute since foo was first called after we'd untraced"))))
