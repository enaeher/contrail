# tracer

Tracer is a library for interactively instrumenting your code in the
REPL during development. It is heavily inspired by the excellent
tracing facilities provided by [SBCL](http://www.sbcl.org/manual/#Function-Tracing).

## Why?

Clojure ships with
[clojure.tools.trace](https://github.com/clojure/tools.trace), which
has some nice features like Cider integration, so why did I write
Tracer?

I found clojure.tools.trace's functionality too limited for my needs,
and its architecture did not lend itself to easy extension in the
directions I wanted to go.

## Documentation

The examples below should get you started, but see the [full API
documentation](https://rawgit.com/enaeher/tracer/master/docs/uberdoc.html) for further reading.

## Usage

### The simple case

```clojure
tracer.core> (defn ensure-even [i]
               (if (odd? i)
                 (inc i)
                 i))
#'tracer.core/ensure-even

tracer.core> (trace #'ensure-even)
#'tracer.core/ensure-even

tracer.core> (ensure-even 3)
 0: (#'tracer.core/ensure-even 3)
 0: #'tracer.core/ensure-even returned 4
4
```

### Nested tracing

```clojure
tracer.core> (defn ensure-all-even [numbers]
               (map ensure-even numbers))
#'tracer.core/ensure-all-even

tracer.core> (trace #'ensure-all-even)
#'tracer.core/ensure-all-even

tracer.core> (ensure-all-even [1 2 3])
 0: (#'tracer.core/ensure-all-even [1 2 3])
  1: (#'tracer.core/ensure-even 1)
  1: #'tracer.core/ensure-even returned 2
  1: (#'tracer.core/ensure-even 2)
  1: #'tracer.core/ensure-even returned 2
  1: (#'tracer.core/ensure-even 3)
  1: #'tracer.core/ensure-even returned 4
 0: #'tracer.core/ensure-all-even returned (2 2 4)
(2 2 4)
```

### Conditional tracing

```clojure
tracer.core> (trace #'ensure-even :when-fn #(odd? %))
#'tracer.core/ensure-even already traced, untracing first.
Untracing #'tracer.core/ensure-even
#'tracer.core/ensure-even

tracer.core> (ensure-all-even [1 2 3])
 0: (#'tracer.core/ensure-all-even [1 2 3])
  1: (#'tracer.core/ensure-even 1)
  1: #'tracer.core/ensure-even returned 2
  1: (#'tracer.core/ensure-even 3)
  1: #'tracer.core/ensure-even returned 4
 0: #'tracer.core/ensure-all-even returned (2 2 4)
(2 2 4)
```

### Tracing a specific arity

```clojure
tracer.core> (defn minimum
               ([] Double/POSITIVE_INFINITY)
               ([n] n)
               ([a b] (if (< a b) a b))
               ([a b & r] (reduce minimum (conj r a b))))
#'tracer.core/minimum

tracer.core> (trace #'minimum :arg-count 2)
#'tracer.core/minimum

tracer.core> (minimum 1 2 3 4 5)
 0: (#'tracer.core/minimum 2 1)
 0: #'tracer.core/minimum returned 1
 0: (#'tracer.core/minimum 1 3)
 0: #'tracer.core/minimum returned 1
 0: (#'tracer.core/minimum 1 4)
 0: #'tracer.core/minimum returned 1
 0: (#'tracer.core/minimum 1 5)
 0: #'tracer.core/minimum returned 1
1
```

### Overriding the default trace reporters

```clojure
tracer.core> (defn many-splendored-identity [& args]
               (map identity args))
#'tracer.core/many-splendored-identity

tracer.core> (trace #'many-splendored-identity
                    :report-before-fn (fn [args]
                                        (pprint/cl-format true "~&~vt~d: (~s ~{~s~^ ~})~%"
                                                          (trace-indent)
                                                          *trace-level*
                                                          richelieu/*current-advised*
                                                          ;; print argument types rather than values	
                                                          (map type args))))
#'tracer.core/many-splendored-identity

tracer.core> (many-splendored-identity {:a 'b :c 'd} 'foo [42] 42 #{})
 0: (#'tracer.core/many-splendored-identity clojure.lang.PersistentArrayMap clojure.lang.Symbol clojure.lang.PersistentVector java.lang.Long clojure.lang.PersistentHashSet)
 0: #'tracer.core/many-splendored-identity returned ({:c d, :a b} foo [42] 42 #{})
({:c d, :a b} foo [42] 42 #{})
```

## Caveats and gotchas

- By default, any lazy sequence returned by a traced function will be
  immediately realized, regardless of whether you are using the
  default `report-after-fn` or your own. This is necessary to ensure
  that that trace output is printed in logical order and that
  `*trace-level*` is bound to the logically-correct value.

  In some cases, you may prefer to disable this behavior, which you
  can do by setting `*force-eager-evaluation*` to false.

- Inline functions and Java methods cannot be traced.

- If you provide a `:when-fn`, it must be able to accept any arity
  with which the traced function is called during tracing; otherwise,
  you will get an error.

- `:arg-count` does not work as a true arity selector, because it
  doesn't allow you to trace only the variadic arity of a multi-arity
  function. To do that, you'll need to do something like:

  ```clojure
  :when-fn (fn [& args] (> (count args) greatest-definite-arity))
  ```

## Todo

- Allow redirection of trace output (without having to rebind `*out*`)

- Add `:within` option

- Add `:count` option, to automatically stop tracing after a certain
  number of calls

- (Maybe) add the ability to serialize (and later re-apply) the current trace
  state

## License

Copyright © 2015 by Eli Naeher

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
