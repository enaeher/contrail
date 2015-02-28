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
documentation](docs/uberdoc.html) for further reading.

## Usage

### The simple case

;; FIXME

## Caveats and gotchas

- By default, any lazy sequence returned by a traced function will be
  immediately realized, regardless of whether you are using the
  default `report-after-fn` or your own. This is necessary to ensure
  that that trace output is printed in logical order and that
  `*trace-level*` is bound to the logically-correct value.

  In some cases, you may prefer to disable this behavior, which you
  can do by setting `*force-eager-evaluation*` to false.

- `:arg-count` does not work as a true arity selector, because it
  doesn't allow you to trace only the variadic arity of a multi-arity
  function. To do that, you'll need to do something like:

  ```clojure
  :when-fn (fn [& args] (> (count args) greatest-definite-arity))
  ```

## License

Copyright © 2015 by Eli Naeher

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
