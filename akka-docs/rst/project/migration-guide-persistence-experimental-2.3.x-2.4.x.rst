.. _migration-guide-persistence-experimental-2.3.x-2.4.x:

##########################################################################
Migration Guide Akka Persistence (experimental) 2.3.3 to 2.3.4 (and 2.4.x)
##########################################################################

**Akka Persistence** is an **experimental module**, which means that neither Binary Compatibility nor API stability
is provided for Persistence while under the *experimental* flag. The goal of this phase is to gather user feedback
before we freeze the APIs in a major release.

defer renamed to deferAsync
===========================
The ``defer`` method in ``PersistentActor`` was renamed to ``deferAsync`` as it matches the semantics
of ``persistAsync`` more closely than ``persist``, which was causing confusion for users.

Its semantics remain unchanged.

Renamed EventsourcedProcessor to PersistentActor
================================================
``EventsourcedProcessor`` is now deprecated and replaced by ``PersistentActor`` which provides the same (and more) API.
Migrating to ``2.4.x`` is as simple as changing all your classes to extending  ``PersistentActor``.

Replace all classes like::

    class DeprecatedProcessor extends EventsourcedProcessor {
      def processorId = "id"
      /*...*/
    }

To extend ``PersistentActor``::

    class NewPersistentProcessor extends PersistentActor {
      def persistenceId = "id"
      /*...*/
    }

Read more about the persistent actor in the :ref:`documentation for Scala <event-sourcing-scala>` and
:ref:`documentation for Java <event-sourcing-java>`.

Changed processorId to (abstract) persistenceId
===============================================
In Akka Persistence ``2.3.3`` and previously, the main building block of applications were Processors.
Persistent messages, as well as processors implemented the ``processorId`` method to identify which persistent entity a message belonged to.

This concept remains the same in Akka ``2.3.4``, yet we rename ``processorId`` to ``persistenceId`` because Processors will be removed,
and persistent messages can be used from different classes not only ``PersistentActor`` (Views, directly from Journals etc).

Please note that ``persistenceId`` is **abstract** in the new API classes (``PersistentActor`` and ``PersistentView``),
and we do **not** provide a default (actor-path derived) value for it like we did for ``processorId``.
The rationale behind this change being stricter de-coupling of your Actor hierarchy and the logical "which persistent entity this actor represents".
A longer discussion on this subject can be found on `issue #15436 <https://github.com/akka/akka/issues/15436>`_ on github.

In case you want to preserve the old behavior of providing the actor's path as the default ``persistenceId``, you can easily
implement it yourself either as a helper trait or simply by overriding ``persistenceId`` as follows::

    override def persistenceId = self.path.toStringWithoutAddress

We provided the renamed method also on already deprecated classes (Channels),
so you can simply apply a global rename of ``processorId`` to ``persistenceId``.

Removed Processor in favour of extending PersistentActor with persistAsync
==========================================================================

The ``Processor`` is now deprecated since ``2.3.4`` and will be removed in ``2.4.x``.
It's semantics replicated in ``PersistentActor`` in the form of an additional ``persist`` method: ``persistAsync``.

In essence, the difference between ``persist`` and ``persistAsync`` is that the former will stash all incoming commands
until all persist callbacks have been processed, whereas the latter does not stash any commands. The new ``persistAsync``
should be used in cases of low consistency yet high responsiveness requirements, the Actor can keep processing incoming
commands, even though not all previous events have been handled.

When these ``persist`` and ``persistAsync`` are used together in the same ``PersistentActor``, the ``persist``
logic will win over the async version so that all guarantees concerning persist still hold. This will however lower
the throughput

Now deprecated code using Processor::

    class OldProcessor extends Processor {
      override def processorId = "user-wallet-1337"

      def receive = {
        case Persistent(cmd) => sender() ! cmd
      }
    }

Replacement code, with the same semantics, using PersistentActor::

    class NewProcessor extends PersistentActor {
      override def persistenceId = "user-wallet-1337"

      def receiveCommand = {
        case cmd =>
          persistAsync(cmd) { e => sender() ! e }
      }

      def receiveRecover = {
        case _ => // logic for handling replay
      }
    }

It is worth pointing out that using ``sender()`` inside the persistAsync callback block is **valid**, and does *not* suffer
any of the problems Futures have when closing over the sender reference.

Using the ``PersistentActor`` instead of ``Processor`` also shifts the responsibility of deciding if a message should be persisted
to the receiver instead of the sender of the message. Previously, using ``Processor``, clients would have to wrap messages as ``Persistent(cmd)``
manually, as well as have to be aware of the receiver being a ``Processor``, which didn't play well with transparency of the ActorRefs in general.

How to migrate data from Processor to PersistentActor
-----------------------------------------------------

The recommended approach for migrating persisted messages from a ``Processor`` to events that can be replayed by
a ``PersistentActor`` is to write a custom migration tool with a ``PersistentView`` and a ``PersistentActor``.
Connect the ``PersistentView`` to the ``persistenceId`` of the old ``Processor`` to replay the stored persistent
messages. Send the messages from the view to a ``PersistentActor`` with another ``persistenceId``. There you can 
transform the old messages to domain events that the real ``PersistentActor`` will be able to understand. Store
the events with ``persistAsync``.

Note that you can implement back-pressure between the writing ``PersistentActor`` and the reading ``PersistentView``
by turning off auto-update in the view and send custom ``Update`` messages to the view with a limited `replayMax`
value.

Removed deleteMessage
=====================

``deleteMessage`` is deprecated and will be removed. When using command sourced ``Processor`` the command was stored before it was
received and could be validated and then there was a reason to remove faulty commands to avoid repeating the error during replay.
When using ``PersistentActor`` you can always validate the command before persisting and therefore the stored event (or command)
should always be valid for replay.

``deleteMessages`` can still be used for pruning of all messages up to a sequence number.


Renamed View to PersistentView, which receives plain messages (Persistent() wrapper is gone)
============================================================================================
Views used to receive messages wrapped as ``Persistent(payload, seqNr)``, this is no longer the case and views receive
the ``payload`` as message from the ``Journal`` directly. The rationale here is that the wrapper approach was inconsistent
with the other Akka Persistence APIs and also is not easily "discoverable" (you have to *know* you will be getting this Persistent wrapper).

Instead, since ``2.3.4``, views get plain messages, and can use additional methods provided by the ``View`` to identify if a message
was sent from the Journal (had been played back to the view). So if you had code like this::

    class AverageView extends View {
      override def processorId = "average-view"

      def receive = {
        case Persistent(msg, seqNr) =>
          // from Journal

        case msg =>
          // from user-land
    }

You should update it to extend ``PersistentView`` instead::

    class AverageView extends PersistentView {
      override def persistenceId = "persistence-sample"
      override def viewId = "persistence-sample-average"

      def receive = {
        case msg if isPersistent =>
          // from Journal
          val seqNr = lastSequenceNr // in case you require the sequence number

        case msg =>
          // from user-land
      }
    }

In case you need to obtain the current sequence number the view is looking at, you can use the ``lastSequenceNr`` method.
It is equivalent to "current sequence number", when ``isPersistent`` returns true, otherwise it yields the sequence number
of the last persistent message that this view was updated with.

Removed Channel and PersistentChannel in favour of AtLeastOnceDelivery trait
============================================================================

One of the primary tasks of a ``Channel`` was to de-duplicate messages that were sent from a
``Processor`` during recovery. Performing external side effects during recovery is not 
encouraged with event sourcing and therefore the ``Channel`` is not needed for this purpose.

The ``Channel`` and ``PersistentChannel`` also performed at-least-once delivery of messages,
but it did not free a sending actor from implementing retransmission or timeouts, since the 
acknowledgement from the channel is needed to guarantee safe hand-off. Therefore at-least-once
delivery is provided in a new ``AtLeastOnceDelivery`` trait that is mixed-in to the
persistent actor on the sending side. 

Read more about at-least-once delivery in the :ref:`documentation for Scala <at-least-once-delivery-scala>` and
:ref:`documentation for Java <at-least-once-delivery-java>`.  

Default persistence plugins
===========================
Previously default ``akka.persistence.journal.plugin`` was set to the LevelDB journal ``akka.persistence.journal.leveldb``
and default ``akka.persistence.snapshot-store.plugin`` was set to the local file-system snapshot ``akka.persistence.snapshot-store.local``.
Now default journal and default snapshot-store plugins are set to empty "" in the persistence extension ``reference.conf``, 
and require explicit user configuration via override in the user ``application.conf``.
This change was needed to decouple persistence extension from the LevelDB dependency, and to support multiple plugin configurations.
Please see persistence extension ``reference.conf`` for details. 

Converted LevelDB to an optional dependency
===========================================
Persistence extension uses LevelDB based plugins for own development and keeps related code in the published jar.
However previously LevelDB was a ``compile`` scope dependency, and now it is an ``optional;provided`` dependency.
To continue using LevelDB based persistence plugins it is now required for related user projects
to include an additional explicit dependency declaration for the LevelDB artifacts. 
This change allows production akka deployments to avoid need for the LevelDB provisioning. 
Please see persistence extension ``reference.conf`` for details. 
