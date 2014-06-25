.. _migration-guide-persistence-experimental-2.3.x-2.4.x:

#####################################################
Migration Guide Akka Persistence (experimental) 2.3.3
#####################################################

**Akka Persistence** is an **experimental module**, which means that neither Binary Compatibility nor API stability
is provided for Persistence while under the *experimental* flag. The goal of this phase is to gather user feedback
before we freeze the APIs in a major release.

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


Changed processorId to (abstract) persistenceId
===============================================
In Akka Persistence ``2.3.3`` and previously, the main building block of applications were Processors.
Persistent messages, as well as processors implemented the ``processorId`` method to identify which persistent entity a message belonged to.

This concept remains the same in Akka ``2.3.4``, yet we rename ``processorId`` to ``persistenceId`` because Processors will be removed,
and persistent messages can be used from different classes not only ``PersistentActor`` (Views, directly from Journals etc).

Please note that ``processorId`` is **abstract** in the new API classes (``PersistentActor`` and ``PersistentView``),
and we do **not** provide a default (actor-path derrived) value for it like we did for ``processorId``.
The rationale behind this change being stricter de-coupling of your Actor hierarchy and the logical "which persistent entity this actor represents".
A longer discussion on this subject can be found on `issue #15436 <https://github.com/akka/akka/issues/15436>`_ on github.

We provided the renamed method also on already deprecated classes (Channels),
so you can simply apply a global rename of ``processorId`` to ``persistenceId``.

Plugin APIs: Renamed PersistentId to PersistenceId
==================================================
Following the removal of Processors and moving to ``persistenceId``, the plugin SPI visible type has changed.
The move from ``2.3.3`` to ``2.3.4`` should be relatively painless, and plugins will work even when using the deprecated ``PersistentId`` type.

Change your implementations from::

    def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]): Future[Unit] = // ...

    def asyncDeleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean): Future[Unit] = {
      val p = messageIds.head.processorId // old
      // ...
    }

to::

    def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]): Future[Unit] = // ...

    def asyncDeleteMessages(messageIds: immutable.Seq[PersistenceId], permanent: Boolean): Future[Unit] = {
      val p = messageIds.head.persistenceId // new
      // ...
    }

Plugins written for ``2.3.3`` are source level compatible with ``2.3.4``, using the deprecated types, but will not work with future releases.
Plugin maintainers are asked to update their plugins to ``2.3.4`` as soon as possible.

Removed Processor in favour of extending PersistentActor with persistAsync
==========================================================================

The ``Processor`` is now deprecated since ``2.3.4`` and will be removed in ``2.4.x``.
It's semantics replicated in ``PersistentActor`` in the form of an additional ``persist`` method: ``persistAsync``.

In essence, the difference betwen ``persist`` and ``persistAsync`` is that the former will stash all incomming commands
until all persist callbacks have been processed, whereas the latter does not stash any commands. The new ``persistAsync``
should be used in cases of low consistency yet high responsiveness requirements, the Actor can keep processing incomming
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

      def receiveEvent = {
        case _ => // logic for handling replay
      }
    }

It is worth pointing out that using ``sender()`` inside the persistAsync callback block is **valid**, and does *not* suffer
any of the problems Futures have when closing over the sender reference.

Using the``PersistentActor`` instead of ``Processor`` also shifts the responsibility of deciding if a message should be persisted
to the receiver instead of the sender of the message. Previously, using ``Processor``, clients would have to wrap messages as ``Persistent(cmd)``
manually, as well as have to be aware of the receiver being a ``Processor``, which didn't play well with transparency of the ActorRefs in general.

Renamed View to PersistentView, which receives plain messages (Persistent() wrapper is gone)
============================================================================================
Views used to receive messages wrapped as ``Persistent(payload, seqNr)``, this is no longer the case and views receive
the ``payload`` as message from the ``Journal`` directly. The rationale here is that the wrapper aproach was inconsistent
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
      override def persistenceId = "average-view"

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
