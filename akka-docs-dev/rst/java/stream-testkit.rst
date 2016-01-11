.. _stream-testkit-java:

###############
Testing streams
###############

Verifying behaviour of Akka Stream sources, flows and sinks can be done using
various code patterns and libraries. Here we will discuss testing these
elements using:

- simple sources, sinks and flows;
- sources and sinks in combination with :class:`TestProbe` from the :mod:`akka-testkit` module;
- sources and sinks specifically crafted for writing tests from the :mod:`akka-stream-testkit` module.

It is important to keep your data processing pipeline as separate sources,
flows and sinks. This makes them easily testable by wiring them up to other
sources or sinks, or some test harnesses that :mod:`akka-testkit` or
:mod:`akka-stream-testkit` provide.

Built in sources, sinks and combinators
=======================================

Testing a custom sink can be as simple as attaching a source that emits
elements from a predefined collection, running a constructed test flow and
asserting on the results that sink produced. Here is an example of a test for a
sink:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#strict-collection

The same strategy can be applied for sources as well. In the next example we
have a source that produces an infinite stream of elements. Such source can be
tested by asserting that first arbitrary number of elements hold some
condition. Here the ``grouped`` combinator and ``Sink.head`` are very useful.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#grouped-infinite

When testing a flow we need to attach a source and a sink. As both stream ends
are under our control, we can choose sources that tests various edge cases of
the flow and sinks that ease assertions.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#folded-stream

TestKit
=======

Akka Stream offers integration with Actors out of the box. This support can be
used for writing stream tests that use familiar :class:`TestProbe` from the
:mod:`akka-testkit` API.

One of the more straightforward tests would be to materialize stream to a
:class:`Future` and then use ``pipe`` pattern to pipe the result of that future
to the probe.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#pipeto-testprobe

Instead of materializing to a future, we can use a :class:`Sink.actorRef` that
sends all incoming elements to the given :class:`ActorRef`. Now we can use
assertion methods on :class:`TestProbe` and expect elements one by one as they
arrive. We can also assert stream completion by expecting for
``onCompleteMessage`` which was given to :class:`Sink.actorRef`.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#sink-actorref

Similarly to :class:`Sink.actorRef` that provides control over received
elements, we can use :class:`Source.actorRef` and have full control over
elements to be sent.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#source-actorref

Streams TestKit
===============

You may have noticed various code patterns that emerge when testing stream
pipelines. Akka Stream has a separate :mod:`akka-stream-testkit` module that
provides tools specifically for writing stream tests. This module comes with
two main components that are :class:`TestSource` and :class:`TestSink` which
provide sources and sinks that materialize to probes that allow fluent API.

.. note::

   Be sure to add the module :mod:`akka-stream-testkit` to your dependencies.

A sink returned by ``TestSink.probe`` allows manual control over demand and
assertions over elements coming downstream.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#test-sink-probe

A source returned by ``TestSource.probe`` can be used for asserting demand or
controlling when stream is completed or ended with an error.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#test-source-probe

You can also inject exceptions and test sink behaviour on error conditions.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#injecting-failure

Test source and sink can be used together in combination when testing flows.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#test-source-and-sink

