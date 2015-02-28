(ns tracer.core-test
  (:require [clojure.test :refer :all]
            [tracer.core :refer :all]))

(use-fixtures :once (fn [f]
                      (f)
                      (untrace-ns *ns*)))

(defn foo "An example function used to test tracing" [])
(defn bar "Another example function used to test nested tracing" [])

(defn- inc-atom [a]
  (swap! a inc))

(deftest trace-state
  (testing "Trace state tracking"
    (trace #'foo)
    (is (traced? #'foo) "Var was traced, but traced? is false")
    (untrace #'foo)
    (is (not (traced? #'foo)) "Var was untraced, but traced? is true")))

(deftest trace-fires
  (testing ":report-before-fn is called"
    (let [call-count (atom 0)
          expected-call-count 5]
      (trace #'foo :report-before-fn (fn [_] (inc-atom call-count)))
      (dotimes [i expected-call-count]
        (foo))
      (is (= expected-call-count @call-count)
          "Trace report function wasn't called the same number of times as the traced function was called"))))

(deftest traced-function-returns-normally
  (testing "Return values of traced functions"
    (with-redefs [foo str]
      (let [example-data [1 2 3 :a :b :c 'x 'y 'z]
            expected-return-values (map foo example-data)]
        (trace #'foo)
        (is (= expected-return-values (map foo example-data))
            "The traced function didn't return the same value as the untraced function.")))))

(deftest trace-level
  (testing "Trace level accurately reflects depth of tracing"
    (with-redefs [foo (fn [] (bar))]
      (trace #'foo :report-before-fn (fn [_] (is (zero? *trace-level*) "*trace-level* should start at 0")))
      (foo)
      (trace #'bar :report-before-fn
             (fn [_] (is (= 1 *trace-level*)
                         "*trace-level* should increase to 1 when called by another traced function")))
      (foo))))

(deftest trace-level-lazy-seqs
  (testing "Trace level interacts correctly with lazy sequences"
    (with-redefs [bar identity
                  foo (fn [thunk] (map bar thunk))]
      (trace #'foo)
      (trace #'bar :report-before-fn
             (fn [_] (is (= 1 *trace-level*)
                         "delayed execution of inner traced functions shouldn't be allowed to mess up *trace-level*")))
      (foo [1 2 3 4]))))

(deftest trace-conditionally
  (testing ":when-fn handling"
    (with-redefs [foo str]
      (let [call-count (atom 0)
            example-data [1 2 3 :a :b :c 'x 'y 'z]
            expected-return-values (map foo example-data)
            expected-call-count 3]
        (trace #'foo :report-before-fn (fn [_] (inc-atom call-count))
               :when-fn #(number? %))
        (is (= expected-return-values (map foo example-data))
            "The function should return normally regardless of the when-fn.")
        (is (= expected-call-count @call-count)
            "The trace report function should only fire when the when-fn returns true.")))))

(deftest trace-arities
  (testing ":arg-count handling"
    (with-redefs [foo (fn
                        ([] (foo 0))
                        ([i] (inc i)))]
      (let [call-count (atom 0)]
        (trace #'foo :report-before-fn (fn [_] (inc-atom call-count)))
        (foo)
        (is (= 2 @call-count)
            "By default, trace should trace all arities")
        (reset! call-count 0)
        (trace #'foo :arg-count 1 :report-before-fn (fn [_] (inc-atom call-count)))
        (foo)
        (is (= 1 @call-count)
            "When an arg-count is specified, trace should trace only calls with that number of args")))))
