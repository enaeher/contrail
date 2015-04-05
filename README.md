# contrail [![Build Status](https://travis-ci.org/enaeher/contrail.svg?branch=master)](https://travis-ci.org/enaeher/contrail)

Contrail is a library for interactively tracing your code in the
REPL during development. It is heavily inspired by the excellent
tracing facilities provided by
[SBCL](http://www.sbcl.org/manual/#Function-Tracing).

[![Clojars Project](http://clojars.org/enaeher/contrail/latest-version.svg)](http://clojars.org/enaeher/contrail)

- [Why?](#why)
- [API Documentation](#api-documentation)
- [Tracing and Lazy Evaluation](#tracing-and-lazy-evaluation)
- [Usage](#usage)
  - [The simple case](#the-simple-case)
  - [What's traced?](#whats-traced)
  - [Conditional tracing](#conditional-tracing)
  - [Tracing a specific arity](#tracing-a-specific-arity)
  - [Tracing one function only within another function](#tracing-one-function-only-within-another-function)
  - [Tracing a limited number of calls](#tracing-a-limited-number-of-calls)
  - [Overriding the default trace reporters](#overriding-the-default-trace-reporters)
  - [Tracing multimethods](#tracing-multimethods)
- [Caveats and gotchas](#caveats-and-gotchas)
- [Todo](#todo)
- [License](#license)

## Why?

Why did I write Contrail when Clojure ships with [clojure.tools.trace](https://github.com/clojure/tools.trace)? The latter's funcationality was too limited for my needs, and its design didn't lent itself to easy extension in the directions I wanted to go.

Contrail's distinguishing features include:
- Control over what the trace output looks like and where it goes
- Control over whether and when lazy sequences are realized by the trace machinery
- Conditional tracing, with helpers for common use cases
- Tracing for a limited number of calls
- Re-compiling a file doesn't blow away trace state for the vars defined there

## API Documentation

The examples below should get you started, but see the [full API
documentation](https://rawgit.com/enaeher/contrail/master/docs/uberdoc.html) for further reading.

## Tracing and Lazy Evaluation

Clojure's lazy sequences add some wrinkles to the tracing concept:

- Trace reporting which prints all arguments to and return values from
  traced functions will cause those values, if they are lazy
  sequences, to be fully realized (even if the traced code would
  normally not realize them, or would do so much later)

- The actual evaluation order and stack may not reflect the "logical"
  call graph as imagined by the programmer.

For these reasons, Contrail can operate in one of two modes,
controlled by the `*force-eager-evaluation*` dynamic var. Two examples
should illustrate the difference:

```clojure
contrail.core> (defn ensure-even [i]
                 (if (odd? i)
                   (inc i)
                   i))
#'contrail.core/ensure-even

contrail.core> (defn ensure-all-even [numbers]
                 (map ensure-even numbers))
#'contrail.core/ensure-all-even

;; *force-eager-evaluation* defaults to true
contrail.core> (ensure-all-even [1 2 3])
 0: (#'contrail.core/ensure-all-even [1 2 3])
  1: (#'contrail.core/ensure-even 1)
  1: #'contrail.core/ensure-even returned 2
  1: (#'contrail.core/ensure-even 2)
  1: #'contrail.core/ensure-even returned 2
  1: (#'contrail.core/ensure-even 3)
  1: #'contrail.core/ensure-even returned 4
 0: #'contrail.core/ensure-all-even returned (2 2 4)
(2 2 4)

contrail.core> (alter-var-root #'*force-eager-evaluation* (constantly false))
false

contrail.core> (ensure-all-even [1 2 3])
 0: (#'contrail.core/ensure-all-even [1 2 3])
 0: #'contrail.core/ensure-all-even returned #<clojure.lang.LazySeq>
 0: (#'contrail.core/ensure-even 1)
 0: #'contrail.core/ensure-even returned 2
 0: (#'contrail.core/ensure-even 2)
 0: #'contrail.core/ensure-even returned 2
 0: (#'contrail.core/ensure-even 3)
 0: #'contrail.core/ensure-even returned 4
(2 2 4)
```

In general, a true value for `*force-eager-evaluation*` will provide
traces which include more information and more closely model the
programmer's (or at least this programmer's) conceptual view of the
code, while a false value will provide traces which don't cause the
potentially-expensive (or impossible) realization of lazy sequences
and which more closely reflect the actual execution order of the
untraced code.

Note that if you provide your own trace reporting functions with the
`:report-before-fn` or `:report-after-fn` args, you are not bound by
`*force-eager-evaluation*` and may realize (nor not) sequences as you
like, with the exception that if `*force-eager-evaluation*` is true,
return values will still be fully realized before your
`:report-after-fn` is called, so that trace output will appear in the
"right" order. One common use-case for custom trace reporting
functions is to more closely control what gets realized; for example,
if you know that a certain argument is likely to be a long sequence
that involves expensive network calls to realize, you might choose to
print only the first few items of the sequence.

## Usage

### The simple case

```clojure
contrail.core> (trace #'ensure-even)
#'contrail.core/ensure-even

contrail.core> (ensure-even 3)
 0: (#'contrail.core/ensure-even 3)
 0: #'contrail.core/ensure-even returned 4
4
```

### What's traced?

```clojure
contrail.core> (traced? #'every?)
false

contrail.core> (trace #'every?)
#'clojure.core/every?

contrail.core> (traced? #'every?)
true

contrail.core> (all-traced)
(#'clojure.core/every?)

contrail.core> (untrace)
Untracing #'clojure.core/every?
nil
```

### Conditional tracing

```clojure
contrail.core> (trace #'ensure-even :when-fn odd?))
#'contrail.core/ensure-even already traced, untracing first.
Untracing #'contrail.core/ensure-even
#'contrail.core/ensure-even

contrail.core> (ensure-all-even [1 2 3])
 0: (#'contrail.core/ensure-all-even [1 2 3])
  1: (#'contrail.core/ensure-even 1)
  1: #'contrail.core/ensure-even returned 2
  1: (#'contrail.core/ensure-even 3)
  1: #'contrail.core/ensure-even returned 4
 0: #'contrail.core/ensure-all-even returned (2 2 4)
(2 2 4)
```

*N.B.:* If you provide a `:when-fn`, it must be able to accept any
arity with which the traced function is called during tracing;
otherwise, you will get an error.

### Tracing a specific arity

```clojure
contrail.core> (defn minimum
                 ([] Double/POSITIVE_INFINITY)
                 ([n] n)
                 ([a b] (if (< a b) a b))
                 ([a b & r] (reduce minimum (conj r a b))))
#'contrail.core/minimum

contrail.core> (trace #'minimum :arity 2)
#'contrail.core/minimum

contrail.core> (minimum 1 2 3 4 5)
 0: (#'contrail.core/minimum 2 1)
 0: #'contrail.core/minimum returned 1
 0: (#'contrail.core/minimum 1 3)
 0: #'contrail.core/minimum returned 1
 0: (#'contrail.core/minimum 1 4)
 0: #'contrail.core/minimum returned 1
 0: (#'contrail.core/minimum 1 5)
 0: #'contrail.core/minimum returned 1
1
```

*N.B.:* - `:arity` does not work as a true arity selector, because it
doesn't allow you to trace only the variadic arity of a multi-arity
function. To do that, you'll need to do something like:

```clojure
:when-fn (fn [& args] (> (count args) greatest-definite-arity))
```

### Tracing one function only within another function

```clojure

contrail.core> (defn foo [])
#'contrail.core/foo

contrail.core> (defn bar [] (foo))
#'contrail.core/bar

contrail.core> (defn baz [] (bar))
#'contrail.core/baz

contrail.core> (trace #'foo :within #'baz)
#'contrail.core/foo

contrail.core> (foo)
nil

contrail.core> (baz)
 0: (#'contrail.core/foo)
 0: #'contrail.core/foo returned nil
nil
```

*N.B.:* `:within` operates by introspecting the current thread's
stack, which means that not only won't it work across newly-created
threads, but it often won't work in contexts where the `:within`
function returns a lazy sequence. Consider the following:

```clojure
(defn x [])

(defn y []
  (map x [1 2 3]))
```

Since `map` (and thus `y`) returns a lazy sequence, the sequence
won't be realized (and `x` won't be called) until after `y` has
returned, which means that if you look at the call stack from within
`x`, `y` will be long gone, so `(trace #'x :within #'y)` will never
print any output. Yes, this is a very frustrating limitation, but
I'm not sure that it's possible to get around it in Clojure.

### Tracing a limited number of calls

```clojure
contrail.core> (trace #'odd? :limit 3)
#'clojure.core/odd?

contrail.core> (map odd? (range 1 10))
 0: (#'clojure.core/odd? 1)
 0: #'clojure.core/odd? returned true
 0: (#'clojure.core/odd? 2)
 0: #'clojure.core/odd? returned false
 0: (#'clojure.core/odd? 3)
 0: #'clojure.core/odd? returned true
Untracing #'clojure.core/odd?
(true false true false true false true false true)
```

### Overriding the default trace reporters

It is sometimes convenient to override the default trace reporting. For example, you may be interested only in a specific argument, and wish to ignore the others; or you may be interested only in the types of the arguments. The `:report-before-fn` and `:report-after-fn` arguments allow you to provide a function which should return a string to use in place of the default output. (Within that function, you can call `current-traced-var` to get the var being traced.)

```clojure
contrail.core> (defn many-splendored-identity [& args]
                 (map identity args))
#'contrail.core/many-splendored-identity

contrail.core> (trace #'many-splendored-identity
                      :report-before-fn
                      (fn [& args]
                        (pprint/cl-format nil "(~s ~{~s~^ ~})" (current-traced-var) (map type args))))
#'contrail.core/many-splendored-identity

contrail.core> (many-splendored-identity {:a 'b :c 'd} 'foo [42] 42 #{})
 0: (#'contrail.core/many-splendored-identity clojure.lang.PersistentArrayMap clojure.lang.Symbol clojure.lang.PersistentVector java.lang.Long clojure.lang.PersistentHashSet)
 0: #'contrail.core/many-splendored-identity returned ({:c d, :a b} foo [42] 42 #{})
({:c d, :a b} foo [42] 42 #{})
```

Or you might want to keep track of which output comes from which thread:

```clojure
contrail.core> (def foo identity)
#'contrail.core/foo

contrail.core> (trace #'foo :report-after-fn (fn [_] (str (current-traced-var) " returned in thread " (Thread/currentThread))))

#'contrail.core/foo
contrail.core> (pmap foo (range 5))
(0 1 2 3 4) 0: (#'contrail.core/foo 0)
 0: (#'contrail.core/foo 3)
 0: (#'contrail.core/foo 1)
 0: (#'contrail.core/foo 4)
 0: (#'contrail.core/foo 2)
 0: #'contrail.core/foo returned in thread Thread[clojure-agent-send-off-pool-36,5,main]
 0: #'contrail.core/foo returned in thread Thread[clojure-agent-send-off-pool-32,5,main]
 0: #'contrail.core/foo returned in thread Thread[clojure-agent-send-off-pool-34,5,main]
 0: #'contrail.core/foo returned in thread Thread[clojure-agent-send-off-pool-35,5,main]
 0: #'contrail.core/foo returned in thread Thread[clojure-agent-send-off-pool-33,5,main]
```

### Tracing multimethods

Multimethods can be traced, but methods can neither be added nor removed from the multimethod (nor existing methods redefined) while the multimethod is traced. However, you can untrace the multimethod, add the new method, and then re-trace (see below). Currently, there is no way to trace only a specific method of a multimethod.

```clojure
contrail.core> (defmulti deep-reverse type)
#'contrail.core/deep-reverse

contrail.core> (defmethod deep-reverse :default [x] x)
#<MultiFn clojure.lang.MultiFn@6b67e98d>

contrail.core> (defmethod deep-reverse clojure.lang.PersistentVector [x] (map deep-reverse (reverse x)))
#<MultiFn clojure.lang.MultiFn@6b67e98d>

contrail.core> (trace #'deep-reverse)
#'contrail.core/deep-reverse

contrail.core> (deep-reverse [1 2 3 [4 5 6 "foobar"]])
 0: (#'contrail.core/deep-reverse [1 2 3 [4 5 6 "foobar"]])
  1: (#'contrail.core/deep-reverse [4 5 6 "foobar"])
    2: (#'contrail.core/deep-reverse "foobar")
    2: #'contrail.core/deep-reverse returned "foobar"
    2: (#'contrail.core/deep-reverse 6)
    2: #'contrail.core/deep-reverse returned 6
    2: (#'contrail.core/deep-reverse 5)
    2: #'contrail.core/deep-reverse returned 5
    2: (#'contrail.core/deep-reverse 4)
    2: #'contrail.core/deep-reverse returned 4
  1: #'contrail.core/deep-reverse returned ("foobar" 6 5 4)
  1: (#'contrail.core/deep-reverse 3)
  1: #'contrail.core/deep-reverse returned 3
  1: (#'contrail.core/deep-reverse 2)
  1: #'contrail.core/deep-reverse returned 2
  1: (#'contrail.core/deep-reverse 1)
  1: #'contrail.core/deep-reverse returned 1
 0: #'contrail.core/deep-reverse returned (("foobar" 6 5 4) 3 2 1)
(("foobar" 6 5 4) 3 2 1)

contrail.core> (defmethod deep-reverse java.lang.String [x] (apply str (map deep-reverse (reverse x))))
ClassCastException clojure.lang.AFunction$1 cannot be cast to clojure.lang.MultiFn  contrail.core/eval115082 (form-init1379520145102690924.clj:1)

contrail.core> (untrace)
Untracing #'contrail.core/deep-reverse
nil

contrail.core> (defmethod deep-reverse java.lang.String [x] (apply str (map deep-reverse (reverse x))))
#<MultiFn clojure.lang.MultiFn@6b67e98d>

contrail.core> (trace #'deep-reverse)
#'contrail.core/deep-reverse

contrail.core> (deep-reverse [1 2 3 [4 5 6 "foobar"]])
 0: (#'contrail.core/deep-reverse [1 2 3 [4 5 6 "foobar"]])
  1: (#'contrail.core/deep-reverse [4 5 6 "foobar"])
    2: (#'contrail.core/deep-reverse "foobar")
      3: (#'contrail.core/deep-reverse \r)
      3: #'contrail.core/deep-reverse returned \r
      3: (#'contrail.core/deep-reverse \a)
      3: #'contrail.core/deep-reverse returned \a
      3: (#'contrail.core/deep-reverse \b)
      3: #'contrail.core/deep-reverse returned \b
      3: (#'contrail.core/deep-reverse \o)
      3: #'contrail.core/deep-reverse returned \o
      3: (#'contrail.core/deep-reverse \o)
      3: #'contrail.core/deep-reverse returned \o
      3: (#'contrail.core/deep-reverse \f)
      3: #'contrail.core/deep-reverse returned \f
    2: #'contrail.core/deep-reverse returned "raboof"
    2: (#'contrail.core/deep-reverse 6)
    2: #'contrail.core/deep-reverse returned 6
    2: (#'contrail.core/deep-reverse 5)
    2: #'contrail.core/deep-reverse returned 5
    2: (#'contrail.core/deep-reverse 4)
    2: #'contrail.core/deep-reverse returned 4
  1: #'contrail.core/deep-reverse returned ("raboof" 6 5 4)
  1: (#'contrail.core/deep-reverse 3)
  1: #'contrail.core/deep-reverse returned 3
  1: (#'contrail.core/deep-reverse 2)
  1: #'contrail.core/deep-reverse returned 2
  1: (#'contrail.core/deep-reverse 1)
  1: #'contrail.core/deep-reverse returned 1
 0: #'contrail.core/deep-reverse returned (("raboof" 6 5 4) 3 2 1)
(("raboof" 6 5 4) 3 2 1)
```

## Caveats and gotchas

- Inline functions and Java methods cannot be traced.

## Todo

- (Maybe) add the ability to serialize (and later re-apply) the current trace
  state

## License

Copyright © 2015 by Eli Naeher

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
