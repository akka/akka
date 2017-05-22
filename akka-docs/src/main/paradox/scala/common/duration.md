# Duration

Durations are used throughout the Akka library, wherefore this concept is
represented by a special data type, `scala.concurrent.duration.Duration`.
Values of this type may represent infinite (`Duration.Inf`,
`Duration.MinusInf`) or finite durations, or be `Duration.Undefined`.

## Finite vs. Infinite

Since trying to convert an infinite duration into a concrete time unit like
seconds will throw an exception, there are different types available for
distinguishing the two kinds at compile time:

 * `FiniteDuration` is guaranteed to be finite, calling `toNanos`
and friends is safe
 * `Duration` can be finite or infinite, so this type should only be used
when finite-ness does not matter; this is a supertype of `FiniteDuration`

## Scala

In Scala durations are constructable using a mini-DSL and support all expected
arithmetic operations:

@@snip [Sample.scala]($code$/scala/docs/duration/Sample.scala) { #dsl }

@@@ note

You may leave out the dot if the expression is clearly delimited (e.g.
within parentheses or in an argument list), but it is recommended to use it
if the time unit is the last token on a line, otherwise semi-colon inference
might go wrong, depending on what starts the next line.

@@@

## Java

Java provides less syntactic sugar, so you have to spell out the operations as
method calls instead:

@@snip [Java.java]($code$/java/jdocs/duration/Java.java) { #import }

@@snip [Java.java]($code$/java/jdocs/duration/Java.java) { #dsl }

## Deadline

Durations have a brother named `Deadline`, which is a class holding a representation
of an absolute point in time, and support deriving a duration from this by calculating the
difference between now and the deadline. This is useful when you want to keep one overall
deadline without having to take care of the book-keeping wrt. the passing of time yourself:

@@snip [Sample.scala]($code$/scala/docs/duration/Sample.scala) { #deadline }

In Java you create these from durations:

@@snip [Java.java]($code$/java/jdocs/duration/Java.java) { #deadline }