# Source.unfold

Stream the result of a function as long as it returns a @scala[`Some`] @java[non empty `Optional`].

@ref[Source operators](../index.md#source-operators)

## Signature

@apidoc[Source.unfold](Source$) { scala="#unfold[S,E](s:S)(f:S=&gt;Option[(S,E)]):akka.stream.scaladsl.Source[E,akka.NotUsed]" java="#unfold(java.lang.Object,akka.japi.function.Function)" }


## Description

Stream the result of a function as long as it returns a @scala[`Some`] @java[non empty `Optional`]. The value inside the option consists of a @scala[tuple] @java[pair] where the first value is a state passed back into the next call to the function allowing to pass a state. The first invocation of the provided fold function will receive the `zero` state. 

@@@ warning

The same `zero` state object will be used for every materialization of the `Source` so it is **mandatory** that the state is immutable. For example a `java.util.Iterator`, `Array` or Java standard library collection would not be safe as the fold operation could mutate the value. If you must use a mutable value, combining with @ref:[Source.lazySource](lazySource.md) to make sure a new mutable `zero` value is created for each materialization is one solution.

@@@

Note that for unfolding a source of elements through a blocking API, such as a network or filesystem resource you should prefer using @ref:[unfoldResource](unfoldResource.md).

## Examples

This first sample starts at a user provided integer and counts down to zero using `unfold` :

Scala
 :   @@snip [Unfold.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Unfold.scala) { #countdown }
 
Java
 :   @@snip [Unfold.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Unfold.java) { #countdown }


It is also possible to express unfolds that don't have an end, which will never return @scala[`None`] @java[`Optional.empty`] and must be combined with for example `.take(n)` to not produce infinite streams. Here we have implemented the Fibonacci numbers (0, 1, 1, 2, 3, 5, 8, 13, etc) with `unfold`:

Scala
 :   @@snip [Unfold.scala](/akka-docs/src/test/scala/docs/stream/operators/source/Unfold.scala) { #fibonacci }
 
Java
 :   @@snip [Unfold.java](/akka-docs/src/test/java/jdocs/stream/operators/source/Unfold.java) { #fibonacci }


## Reactive Streams semantics

@@@div { .callout }

**emits** when there is demand and the unfold function over the previous state returns non empty value

**completes** when the unfold function returns an empty value

@@@

