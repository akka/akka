.. _stream-integration-reactive-streams-scala:

Integrating with Reactive Streams
=================================

`Reactive Streams`_ defines a standard for asynchronous stream processing with non-blocking
back pressure. It makes it possible to plug together stream libraries that adhere to the standard.
Akka Streams is one such library.

An incomplete list of other implementations:

* `Reactor (1.1+)`_
* `RxJava`_
* `Ratpack`_
* `Slick`_

.. _Reactive Streams: http://reactive-streams.org/
.. _Reactor (1.1+): http://github.com/reactor/reactor
.. _RxJava: https://github.com/ReactiveX/RxJavaReactiveStreams
.. _Ratpack: http://www.ratpack.io/manual/current/streams.html
.. _Slick: http://slick.typesafe.com

The two most important interfaces in Reactive Streams are the :class:`Publisher` and :class:`Subscriber`.

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#imports

Let us assume that a library provides a publisher of tweets:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#tweets-publisher

and another library knows how to store author handles in a database:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#author-storage-subscriber

Using an Akka Streams :class:`Flow` we can transform the stream and connect those:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala
   :include: authors,connect-all

The :class:`Publisher` is used as an input :class:`Source` to the flow and the 
:class:`Subscriber` is used as an output :class:`Sink`.

A :class:`Flow` can also be materialized to a :class:`Subscriber`, :class:`Publisher` pair:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#flow-publisher-subscriber

A publisher can be connected to a subscriber with the ``subscribe`` method.

It is also possible to expose a :class:`Source` as a :class:`Publisher`
by using the ``publisher`` :class:`Sink`:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#source-publisher

A publisher that is created with ``Sink.publisher`` only supports one subscriber. A second 
subscription attempt will be rejected with an :class:`IllegalStateException`.

A publisher that supports multiple subscribers can be created with ``Sink.fanoutPublisher``
instead:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala
   :include: author-alert-subscriber,author-storage-subscriber

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#source-fanoutPublisher
 
The buffer size controls how far apart the slowest subscriber can be from the fastest subscriber
before slowing down the stream.

To make the picture complete, it is also possible to expose a :class:`Sink` as a :class:`Subscriber`
by using the ``subscriber`` :class:`Source`:

.. includecode:: code/docs/stream/ReactiveStreamsDocSpec.scala#sink-subscriber





 