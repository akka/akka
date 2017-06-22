# Testing streams

Verifying behaviour of Akka Stream sources, flows and sinks can be done using
various code patterns and libraries. Here we will discuss testing these
elements using:

 * simple sources, sinks and flows;
 * sources and sinks in combination with `TestProbe` from the `akka-testkit` module;
 * sources and sinks specifically crafted for writing tests from the `akka-stream-testkit` module.

It is important to keep your data processing pipeline as separate sources,
flows and sinks. This makes them easily testable by wiring them up to other
sources or sinks, or some test harnesses that `akka-testkit` or
`akka-stream-testkit` provide.

## Built in sources, sinks and combinators

Testing a custom sink can be as simple as attaching a source that emits
elements from a predefined collection, running a constructed test flow and
asserting on the results that sink produced. Here is an example of a test for a
sink:

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #strict-collection }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #strict-collection }

The same strategy can be applied for sources as well. In the next example we
have a source that produces an infinite stream of elements. Such source can be
tested by asserting that first arbitrary number of elements hold some
condition. Here the `take` combinator and `Sink.seq` are very useful.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #grouped-infinite }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #grouped-infinite }

When testing a flow we need to attach a source and a sink. As both stream ends
are under our control, we can choose sources that tests various edge cases of
the flow and sinks that ease assertions.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #folded-stream }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #folded-stream }

## TestKit

Akka Stream offers integration with Actors out of the box. This support can be
used for writing stream tests that use familiar `TestProbe` from the
`akka-testkit` API.

One of the more straightforward tests would be to materialize stream to a
@scala[`Future`]@java[`CompletionStage`] and then use @scala[`pipe`]@java[`PatternsCS.pipe`] pattern to pipe the result of that future
to the probe.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #pipeto-testprobe }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #pipeto-testprobe }

Instead of materializing to a future, we can use a `Sink.actorRef` that
sends all incoming elements to the given `ActorRef`. Now we can use
assertion methods on `TestProbe` and expect elements one by one as they
arrive. We can also assert stream completion by expecting for
`onCompleteMessage` which was given to `Sink.actorRef`.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #sink-actorref }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #sink-actorref }

Similarly to `Sink.actorRef` that provides control over received
elements, we can use `Source.actorRef` and have full control over
elements to be sent.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #source-actorref }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #source-actorref }

## Streams TestKit

You may have noticed various code patterns that emerge when testing stream
pipelines. Akka Stream has a separate `akka-stream-testkit` module that
provides tools specifically for writing stream tests. This module comes with
two main components that are `TestSource` and `TestSink` which
provide sources and sinks that materialize to probes that allow fluent API.

@@@ note

Be sure to add the module `akka-stream-testkit` to your dependencies.

@@@

A sink returned by `TestSink.probe` allows manual control over demand and
assertions over elements coming downstream.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #test-sink-probe }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #test-sink-probe }

A source returned by `TestSource.probe` can be used for asserting demand or
controlling when stream is completed or ended with an error.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #test-source-probe }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #test-source-probe }

You can also inject exceptions and test sink behaviour on error conditions.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #injecting-failure }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #injecting-failure }

Test source and sink can be used together in combination when testing flows.

Scala
:   @@snip [StreamTestKitDocSpec.scala]($code$/scala/docs/stream/StreamTestKitDocSpec.scala) { #test-source-and-sink }

Java
:   @@snip [StreamTestKitDocTest.java]($code$/java/jdocs/stream/StreamTestKitDocTest.java) { #test-source-and-sink }

## Fuzzing Mode

For testing, it is possible to enable a special stream execution mode that exercises concurrent execution paths
more aggressively (at the cost of reduced performance) and therefore helps exposing race conditions in tests. To
enable this setting add the following line to your configuration:

```
akka.stream.materializer.debug.fuzzing-mode = on
```

@@@ warning

Never use this setting in production or benchmarks. This is a testing tool to provide more coverage of your code
during tests, but it reduces the throughput of streams. A warning message will be logged if you have this setting
enabled.

@@@