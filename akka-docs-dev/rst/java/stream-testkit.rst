.. _stream-testkit-java:

###############
Testing streams
###############

Akka Streams comes with an :mod:`akka-stream-testkit` module that provides tools which can be used for controlling and asserting various parts of the stream pipeline.

Probe Sink
==========

Using probe as a `Sink` allows manual control over demand and assertions over elements coming downstream. Streams testkit provides a sink that materializes to a :class:`TestSubscriber.Probe`.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#test-sink-probe

Probe Source
============

A source that materializes to :class:`TestPublisher.Probe` can be used for asserting demand or controlling when stream is completed or ended with an error.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/StreamTestKitDocTest.java#test-source-probe

*TODO*

List by example various operations on probes. Using probes without a sink.
