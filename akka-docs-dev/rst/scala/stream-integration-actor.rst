.. _stream-integration-actor-scala:

Integrating with Actors
=======================

:class:`ActorPublisher` and :class:`ActorSubscriber` are two traits that provides support for
implementing Reactive Streams :class:`Publisher` and :class:`Subscriber` with an :class:`Actor`.

These can be consumed by other Reactive Stream libraries or used as a 
Akka Streams :class:`Source` or :class:`Sink`.

.. warning::

  :class:`ActorPublisher` and :class:`ActorSubscriber` cannot be used with remote actors,
  because if signals of the Reactive Streams protocol (e.g. ``request``) are lost the 
  the stream may deadlock.

ActorPublisher
^^^^^^^^^^^^^^

Extend/mixin :class:`akka.stream.actor.ActorPublisher` in your :class:`Actor` to make it a
stream publisher that keeps track of the subscription life cycle and requested elements.

Here is an example of such an actor. It dispatches incoming jobs to the attached subscriber:

.. includecode:: code/docs/stream/ActorPublisherDocSpec.scala#job-manager

You send elements to the stream by calling ``onNext``. You are allowed to send as many
elements as have been requested by the stream subscriber. This amount can be inquired with
``totalDemand``. It is only allowed to use ``onNext`` when ``isActive`` and ``totalDemand>0``,
otherwise ``onNext`` will throw ``IllegalStateException``.

When the stream subscriber requests more elements the ``ActorPublisher.Request`` message
is delivered to this actor, and you can act on that event. The ``totalDemand``
is updated automatically.

When the stream subscriber cancels the subscription the ``ActorPublisher.Cancel`` message
is delivered to this actor. After that subsequent calls to ``onNext`` will be ignored.

You can complete the stream by calling ``onComplete``. After that you are not allowed to
call ``onNext``, ``onError`` and ``onComplete``.

You can terminate the stream with failure by calling ``onError``. After that you are not allowed to
call ``onNext``, ``onError`` and ``onComplete``.

If you suspect that this ``ActorPublisher`` may never get subscribed to, you can override the ``subscriptionTimeout``
method to provide a timeout after which this Publisher should be considered canceled. The actor will be notified when
the timeout triggers via an ``ActorPublisherMessage.SubscriptionTimeoutExceeded`` message and MUST then perform 
cleanup and stop itself.

If the actor is stopped the stream will be completed, unless it was not already terminated with
failure, completed or canceled.

More detailed information can be found in the API documentation.

This is how it can be used as input :class:`Source` to a :class:`Flow`:

.. includecode:: code/docs/stream/ActorPublisherDocSpec.scala#actor-publisher-usage

You can only attach one subscriber to this publisher. Use ``Sink.fanoutPublisher`` to enable
multiple subscribers.

ActorSubscriber
^^^^^^^^^^^^^^^

Extend/mixin :class:`akka.stream.actor.ActorSubscriber` in your :class:`Actor` to make it a
stream subscriber with full control of stream back pressure. It will receive
``ActorSubscriberMessage.OnNext``, ``ActorSubscriberMessage.OnComplete`` and ``ActorSubscriberMessage.OnError``
messages from the stream. It can also receive other, non-stream messages, in the same way as any actor.

Here is an example of such an actor. It dispatches incoming jobs to child worker actors:

.. includecode:: code/docs/stream/ActorSubscriberDocSpec.scala#worker-pool

Subclass must define the ``RequestStrategy`` to control stream back pressure.
After each incoming message the ``ActorSubscriber`` will automatically invoke
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

.. includecode:: code/docs/stream/ActorSubscriberDocSpec.scala#actor-subscriber-usage