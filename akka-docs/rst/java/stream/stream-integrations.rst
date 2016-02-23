.. _stream-integrations-java:

###########
Integration
###########

Integrating with Actors
=======================

For piping the elements of a stream as messages to an ordinary actor you can use the
``Sink.actorRef``. Messages can be sent to a stream via the :class:`ActorRef` that is
materialized by ``Source.actorRef``.

For more advanced use cases the :class:`ActorPublisher` and :class:`ActorSubscriber` traits are
provided to support implementing Reactive Streams :class:`Publisher` and :class:`Subscriber` with
an :class:`Actor`.

These can be consumed by other Reactive Stream libraries or used as a
Akka Streams :class:`Source` or :class:`Sink`.

.. warning::

  :class:`AbstractActorPublisher` and :class:`AbstractActorSubscriber` cannot be used with remote actors,
  because if signals of the Reactive Streams protocol (e.g. ``request``) are lost the
  the stream may deadlock.

.. note::
  These Actors are designed to be implemented using Java 8 lambda expressions. In case you need to stay on a JVM
  prior to 8, Akka provides :class:`UntypedActorPublisher` and :class:`UntypedActorSubscriber` which can be used
  easily from any language level.

Source.actorRef
^^^^^^^^^^^^^^^

Messages sent to the actor that is materialized by ``Source.actorRef`` will be emitted to the
stream if there is demand from downstream, otherwise they will be buffered until request for
demand is received.

Depending on the defined :class:`OverflowStrategy` it might drop elements if there is no space
available in the buffer. The strategy ``OverflowStrategy.backpressure()`` is not supported
for this Source type, you should consider using ``ActorPublisher`` if you want a backpressured
actor interface.

The stream can be completed successfully by sending ``akka.actor.PoisonPill`` or
``akka.actor.Status.Success`` to the actor reference.

The stream can be completed with failure by sending ``akka.actor.Status.Failure`` to the
actor reference.

The actor will be stopped when the stream is completed, failed or cancelled from downstream,
i.e. you can watch it to get notified when that happens.

Sink.actorRef
^^^^^^^^^^^^^

The sink sends the elements of the stream to the given `ActorRef`. If the target actor terminates
the stream will be cancelled. When the stream is completed successfully the given ``onCompleteMessage``
will be sent to the destination actor. When the stream is completed with failure a ``akka.actor.Status.Failure``
message will be sent to the destination actor.

.. warning::

   There is no back-pressure signal from the destination actor, i.e. if the actor is not consuming
   the messages fast enough the mailbox of the actor will grow. For potentially slow consumer actors
   it is recommended to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   limiting stage in front of this stage.

ActorPublisher
^^^^^^^^^^^^^^

Extend :class:`akka.stream.actor.AbstractActorPublisher` to implement a
stream publisher that keeps track of the subscription life cycle and requested elements.

Here is an example of such an actor. It dispatches incoming jobs to the attached subscriber:

.. includecode:: ../code/docs/stream/ActorPublisherDocTest.java#job-manager

You send elements to the stream by calling ``onNext``. You are allowed to send as many
elements as have been requested by the stream subscriber. This amount can be inquired with
``totalDemand``. It is only allowed to use ``onNext`` when ``isActive`` and ``totalDemand>0``,
otherwise ``onNext`` will throw ``IllegalStateException``.

When the stream subscriber requests more elements the ``ActorPublisherMessage.Request`` message
is delivered to this actor, and you can act on that event. The ``totalDemand``
is updated automatically.

When the stream subscriber cancels the subscription the ``ActorPublisherMessage.Cancel`` message
is delivered to this actor. After that subsequent calls to ``onNext`` will be ignored.

You can complete the stream by calling ``onComplete``. After that you are not allowed to
call ``onNext``, ``onError`` and ``onComplete``.

You can terminate the stream with failure by calling ``onError``. After that you are not allowed to
call ``onNext``, ``onError`` and ``onComplete``.

If you suspect that this ``AbstractActorPublisher`` may never get subscribed to, you can override the ``subscriptionTimeout``
method to provide a timeout after which this Publisher should be considered canceled. The actor will be notified when
the timeout triggers via an ``ActorPublisherMessage.SubscriptionTimeoutExceeded`` message and MUST then perform
cleanup and stop itself.

If the actor is stopped the stream will be completed, unless it was not already terminated with
failure, completed or canceled.

More detailed information can be found in the API documentation.

This is how it can be used as input :class:`Source` to a :class:`Flow`:

.. includecode:: ../code/docs/stream/ActorPublisherDocTest.java#actor-publisher-usage

You can only attach one subscriber to this publisher. Use a ``Broadcast``-element or
attach a ``Sink.asPublisher(AsPublisher.WITH_FANOUT)`` to enable multiple subscribers.

ActorSubscriber
^^^^^^^^^^^^^^^

Extend :class:`akka.stream.actor.AbstractActorSubscriber` to make your class a stream subscriber with
full control of stream back pressure. It will receive
``ActorSubscriberMessage.OnNext``, ``ActorSubscriberMessage.OnComplete`` and ``ActorSubscriberMessage.OnError``
messages from the stream. It can also receive other, non-stream messages, in the same way as any actor.

Here is an example of such an actor. It dispatches incoming jobs to child worker actors:

.. includecode:: ../code/docs/stream/ActorSubscriberDocTest.java#worker-pool

Subclass must define the ``RequestStrategy`` to control stream back pressure.
After each incoming message the ``AbstractActorSubscriber`` will automatically invoke
the ``RequestStrategy.requestDemand`` and propagate the returned demand to the stream.

* The provided ``WatermarkRequestStrategy`` is a good strategy if the actor performs work itself.
* The provided ``MaxInFlightRequestStrategy`` is useful if messages are queued internally or
  delegated to other actors.
* You can also implement a custom ``RequestStrategy`` or call ``request`` manually together with
  ``ZeroRequestStrategy`` or some other strategy. In that case
  you must also call ``request`` when the actor is started or when it is ready, otherwise
  it will not receive any elements.

More detailed information can be found in the API documentation.

This is how it can be used as output :class:`Sink` to a :class:`Flow`:

.. includecode:: ../code/docs/stream/ActorSubscriberDocTest.java#actor-subscriber-usage

Integrating with External Services
==================================

Stream transformations and side effects involving external non-stream based services can be
performed with ``mapAsync`` or ``mapAsyncUnordered``.

For example, sending emails to the authors of selected tweets using an external
email service:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#email-server-send

We start with the tweet stream of authors:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#tweet-authors

Assume that we can lookup their email address using:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#email-address-lookup

Transforming the stream of authors to a stream of email addresses by using the ``lookupEmail``
service can be done with ``mapAsync``:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#email-addresses-mapAsync

Finally, sending the emails:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#send-emails

``mapAsync`` is applying the given function that is calling out to the external service to
each of the elements as they pass through this processing step. The function returns a :class:`CompletionStage`
and the value of that future will be emitted downstreams. The number of Futures
that shall run in parallel is given as the first argument to ``mapAsync``.
These Futures may complete in any order, but the elements that are emitted
downstream are in the same order as received from upstream.

That means that back-pressure works as expected. For example if the ``emailServer.send``
is the bottleneck it will limit the rate at which incoming tweets are retrieved and
email addresses looked up.

The final piece of this pipeline is to generate the demand that pulls the tweet
authors information through the emailing pipeline: we attach a ``Sink.ignore``
which makes it all run. If our email process would return some interesting data
for further transformation then we would of course not ignore it but send that
result stream onwards for further processing or storage.

Note that ``mapAsync`` preserves the order of the stream elements. In this example the order
is not important and then we can use the more efficient ``mapAsyncUnordered``:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#external-service-mapAsyncUnordered

In the above example the services conveniently returned a :class:`CompletionStage` of the result.
If that is not the case you need to wrap the call in a :class:`CompletionStage`. If the service call
involves blocking you must also make sure that you run it on a dedicated execution context, to
avoid starvation and disturbance of other tasks in the system.

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#blocking-mapAsync

The configuration of the ``"blocking-dispatcher"`` may look something like:

.. includecode:: ../../scala/code/docs/stream/IntegrationDocSpec.scala#blocking-dispatcher-config

An alternative for blocking calls is to perform them in a ``map`` operation, still using a
dedicated dispatcher for that operation.

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#blocking-map

However, that is not exactly the same as ``mapAsync``, since the ``mapAsync`` may run
several calls concurrently, but ``map`` performs them one at a time.

For a service that is exposed as an actor, or if an actor is used as a gateway in front of an
external service, you can use ``ask``:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#save-tweets

Note that if the ``ask`` is not completed within the given timeout the stream is completed with failure.
If that is not desired outcome you can use ``recover`` on the ``ask`` :class:`CompletionStage`.

Illustrating ordering and parallelism
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Let us look at another example to get a better understanding of the ordering
and parallelism characteristics of ``mapAsync`` and ``mapAsyncUnordered``.

Several ``mapAsync`` and ``mapAsyncUnordered`` futures may run concurrently.
The number of concurrent futures are limited by the downstream demand.
For example, if 5 elements have been requested by downstream there will be at most 5
futures in progress.

``mapAsync`` emits the future results in the same order as the input elements
were received. That means that completed results are only emitted downstream
when earlier results have been completed and emitted. One slow call will thereby
delay the results of all successive calls, even though they are completed before
the slow call.

``mapAsyncUnordered`` emits the future results as soon as they are completed, i.e.
it is possible that the elements are not emitted downstream in the same order as
received from upstream. One slow call will thereby not delay the results of faster
successive calls as long as there is downstream demand of several elements.

Here is a fictive service that we can use to illustrate these aspects.

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#sometimes-slow-service

Elements starting with a lower case character are simulated to take longer time
to process.

Here is how we can use it with ``mapAsync``:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#sometimes-slow-mapAsync

The output may look like this:

::

	before: a
	before: B
	before: C
	before: D
	running: a (1)
	running: B (2)
	before: e
	running: C (3)
	before: F
	running: D (4)
	before: g
	before: H
	completed: C (3)
	completed: B (2)
	completed: D (1)
	completed: a (0)
	after: A
	after: B
	running: e (1)
	after: C
	after: D
	running: F (2)
	before: i
	before: J
	running: g (3)
	running: H (4)
	completed: H (2)
	completed: F (3)
	completed: e (1)
	completed: g (0)
	after: E
	after: F
	running: i (1)
	after: G
	after: H
	running: J (2)
	completed: J (1)
	completed: i (0)
	after: I
	after: J

Note that ``after`` lines are in the same order as the ``before`` lines even
though elements are ``completed`` in a different order. For example ``H``
is ``completed`` before ``g``, but still emitted afterwards.

The numbers in parenthesis illustrates how many calls that are in progress at
the same time. Here the downstream demand and thereby the number of concurrent
calls are limited by the buffer size (4) of the :class:`ActorMaterializerSettings`.

Here is how we can use the same service with ``mapAsyncUnordered``:

.. includecode:: ../code/docs/stream/IntegrationDocTest.java#sometimes-slow-mapAsyncUnordered

The output may look like this:

::

	before: a
	before: B
	before: C
	before: D
	running: a (1)
	running: B (2)
	before: e
	running: C (3)
	before: F
	running: D (4)
	before: g
	before: H
	completed: B (3)
	completed: C (1)
	completed: D (2)
	after: B
	after: D
	running: e (2)
	after: C
	running: F (3)
	before: i
	before: J
	completed: F (2)
	after: F
	running: g (3)
	running: H (4)
	completed: H (3)
	after: H
	completed: a (2)
	after: A
	running: i (3)
	running: J (4)
	completed: J (3)
	after: J
	completed: e (2)
	after: E
	completed: g (1)
	after: G
	completed: i (0)
	after: I

Note that ``after`` lines are not in the same order as the ``before`` lines. For example
``H`` overtakes the slow ``G``.

The numbers in parenthesis illustrates how many calls that are in progress at
the same time. Here the downstream demand and thereby the number of concurrent
calls are limited by the buffer size (4) of the :class:`ActorMaterializerSettings`.

.. _reactive-streams-integration-java:

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
.. _Slick: http://slick.lightbend.com

The two most important interfaces in Reactive Streams are the :class:`Publisher` and :class:`Subscriber`.

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#imports

Let us assume that a library provides a publisher of tweets:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#tweets-publisher

and another library knows how to store author handles in a database:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#author-storage-subscriber

Using an Akka Streams :class:`Flow` we can transform the stream and connect those:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java
  :include: authors,connect-all

The :class:`Publisher` is used as an input :class:`Source` to the flow and the
:class:`Subscriber` is used as an output :class:`Sink`.

A :class:`Flow` can also be also converted to a :class:`RunnableGraph[Processor[In, Out]]` which
materializes to a :class:`Processor` when ``run()`` is called. ``run()`` itself can be called multiple
times, resulting in a new :class:`Processor` instance each time.

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#flow-publisher-subscriber

A publisher can be connected to a subscriber with the ``subscribe`` method.

It is also possible to expose a :class:`Source` as a :class:`Publisher`
by using the Publisher-:class:`Sink`:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#source-publisher

A publisher that is created with ``Sink.asPublisher(AsPublisher.WITHOUT_FANOUT)`` supports only a single subscription.
Additional subscription attempts will be rejected with an :class:`IllegalStateException`.

A publisher that supports multiple subscribers using fan-out/broadcasting is created as follows:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java
  :include: author-alert-subscriber,author-storage-subscriber

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#source-fanoutPublisher

The input buffer size of the stage controls how far apart the slowest subscriber can be from the fastest subscriber
before slowing down the stream.

To make the picture complete, it is also possible to expose a :class:`Sink` as a :class:`Subscriber`
by using the Subscriber-:class:`Source`:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#sink-subscriber

It is also possible to use re-wrap :class:`Processor` instances as a :class:`Flow` by
passing a factory function that will create the :class:`Processor` instances:

.. includecode:: ../code/docs/stream/ReactiveStreamsDocTest.java#use-processor

Please note that a factory is necessary to achieve reusability of the resulting :class:`Flow`.
