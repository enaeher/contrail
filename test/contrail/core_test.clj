(ns contrail.core-test
  (:require [clojure.test :refer :all]
            [contrail.core :refer :all]))

(use-fixtures :each (fn [f] (untrace) (f)))

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
                         "Delayed execution of inner traced functions shouldn't be allowed to mess up *trace-level*")))
      (foo [1 2 3 4]))))

(deftest trace-conditionally
  (testing ":when-fn handling"
    (with-redefs [foo str]
      (let [call-count (atom 0)
            example-data [1 2 3 :a :b :c 'x 'y 'z]
            expected-return-values (map foo example-data)
            expected-call-count (count (filter number? example-data))]
        (trace #'foo :report-before-fn (fn [_] (inc-atom call-count))
               :when-fn number?)
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

(deftest trace-within
  (testing ":within handling"
    (with-redefs [foo (fn [] (bar))]
      (let [call-count (atom 0)]
        (trace #'bar :within #'foo :report-before-fn (fn [_] (inc-atom call-count)))
        (bar)
        (is (zero? @call-count)
            "The trace report function should not fire when a :within function is provided but is not on the stack.")
        (foo)
        (is (= 1 @call-count)
            "The trace report function should fire when a :within function is provided and is on the stack.")))))

(deftest trace-limit
  (testing ":limit handling"
    (let [call-count (atom 0)
          expected-call-count 5]
      (trace #'foo :limit expected-call-count :report-before-fn (fn [_] (inc-atom call-count)))
      (dotimes [i 10]
        (foo))
      (is (= expected-call-count @call-count)
          "The trace report function should fire no more than the number of times specified.")
      (is (not (traced? #'foo))
          "Once the traced function has been called the specified number of times, it should be untraced."))))

(deftest trace-limit-multiple-traces
  (testing ":limit handling with multiple traced vars"
    (let [foo-call-count (atom 0)
          expected-foo-call-count 5
          bar-call-count (atom 0)
          expected-bar-call-count 7]
      (trace #'foo :limit expected-foo-call-count :report-before-fn (fn [_] (inc-atom foo-call-count)))
      (trace #'bar :limit expected-bar-call-count :report-before-fn (fn [_] (inc-atom bar-call-count)))
      (dotimes [i 10]
        (foo)
        (bar))
      (is (= expected-foo-call-count @foo-call-count)
          "The trace report function should fire no more than the number of times specified.")
      (is (= expected-bar-call-count @bar-call-count)
          "The trace report function should fire no more than the number of times specified."))))

(deftest trace-limit-conditional
  (testing "interaction of :limit and :when-fn"
    (with-redefs [foo identity]
      (let [call-count (atom 0)
            example-data [1 :b 2 :c 3 :d 4 :e 5 :f]
            limit 2
            expected-call-count (min (count (filter keyword? example-data)) limit)
            expected-return-values (map foo example-data)]
        (trace #'foo :limit limit :when-fn keyword? :report-before-fn (fn [_] (inc-atom call-count)))
        (is (= expected-return-values (map foo example-data))
            "The traced function didn't return the same value as the untraced function.")
        (is (= expected-call-count @call-count)
            "When a :when-fn is specified, only calls which match the :when-fn should count toward the limit.")))))

(deftest trace-out
  (testing "*trace-out*"
    (binding [*trace-out* (new java.io.StringWriter)]
      (is (empty? (with-out-str
                    (trace #'foo)
                    (foo)))
          "Trace reporting shouldn't produce anything on *out* when *trace-out* is bound to something else.")
      (is (seq (str *trace-out*))
          "Trace reporting should produce output on *trace-out*"))))
