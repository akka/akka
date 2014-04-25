.. _migration-2.4:

################################
 Migration Guide 2.3.x to 2.4.x
################################

The 2.4 release contains some structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from earlier versions you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`
and then :ref:`2.1.x to 2.2.x <migration-2.2>` and then :ref:`2.2.x to 2.3.x <migration-2.3>`.

TestKit.remaining throws AssertionError
=======================================

In earlier versions of Akka `TestKit.remaining` returned the default timeout configurable under
"akka.test.single-expect-default". This was a bit confusing and thus it has been changed to throw an
AssertionError if called outside of within. The old behavior however can still be achieved by
calling `TestKit.remainingOrDefault` instead.

EventStream and ActorClassification EventBus now require an ActorSystem
=======================================================================

Both the ``EventStream`` (:ref:`Scala <event-stream-scala>`, :ref:`Java <event-stream-java>`) and the
``ActorClassification`` Event Bus (:ref:`Scala <actor-classification-scala>`, :ref:`Java <actor-classification-java>`) now
require an ``ActorSystem`` to properly operate. The reason for that is moving away from stateful internal lifecycle checks
to a fully reactive model for unsubscribing actors that have ``Terminated``.

If you have implemented a custom event bus, you will need to pass in the actor system through the constructor now:

.. includecode:: ../scala/code/docs/event/EventBusDocSpec.scala#actor-bus

If you have been creating EventStreams manually, you now have to provide an actor system and *start the unsubscriber*:

.. includecode:: ../../../akka-actor-tests/src/test/scala/akka/event/EventStreamSpec.scala#event-bus-start-unsubscriber-scala

Please note that this change affects you only if you have implemented your own busses, Akka's own ``context.eventStream``
is still there and does not require any attention from you concerning this change.

Removed Deprecated Features
===========================

The following, previously deprecated, features have been removed:

* akka-dataflow

* akka-transactor

* durable mailboxes (akka-mailboxes-common, akka-file-mailbox)

* Cluster.publishCurrentClusterState

* akka.cluster.auto-down, replaced by akka.cluster.auto-down-unreachable-after in Akka 2.3

* Old routers and configuration.

  Note that in router configuration you must now specify if it is a ``pool`` or a ``group``
  in the way that was introduced in Akka 2.3.

* Timeout constructor without unit
 
* JavaLoggingEventHandler, replaced by JavaLogger

* UntypedActorFactory

* Java API TestKit.dilated, moved to JavaTestKit.dilated

