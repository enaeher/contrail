(ns contrail.within-test
  (:require [contrail.within :refer :all]
            [clojure.test :refer :all]
            [contrail.within-test-namespace :as test-ns]))

(defn test-fn-1 [f]
  (f))

(defn test-fn-2 [f]
  (f))

(deftest var-bound-functions
  (testing "true within var-bound function"
    (test-fn-1
     #(is (within? test-fn-1))))
  (testing "true when in nested function"
    (test-fn-1
     #(test-fn-2
       (fn []
         (is (within? test-fn-1))))))
  (testing "false outside of var-bound function"
    (is (not (within? test-fn-2)))))

(deftest local-functions
  (letfn [(local-fn [f]
            (f))]
    (testing "true within local named function"
      (local-fn
       #(is (within? local-fn))))
    (testing "false outside of local named function"
      (is (not (within? local-fn))))))

(deftest works-across-namespaces
  (testing "should return false when within a same-named function in a different ns"
    (is (not (test-ns/test-fn-1 #(within? test-fn-1)))))
  (testing "should return true when within the specified function, even if that is in a different ns"
    (is (test-ns/test-fn-1 #(within? test-ns/test-fn-1)))))
