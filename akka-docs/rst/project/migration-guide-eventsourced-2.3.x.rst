.. _migration-eventsourced-2.3:

######################################################
Migration Guide Eventsourced to Akka Persistence 2.3.x
######################################################

General notes
=============

`Eventsourced`_ and Akka :ref:`persistence-scala` share many high-level concepts but strongly differ on design,
implementation and usage level. This migration guide is more a higher-level comparison of Eventsourced and Akka
Persistence rather than a sequence of low-level instructions how to transform Eventsourced application code into
Akka Persistence application code. This should provide a good starting point for a migration effort. Development
teams should consult the user documentation of both projects for further details.

.. _Eventsourced: https://github.com/eligosource/eventsourced

Scope of this migration guide is code migration, not journal migration. Journals written by Eventsourced can neither
be used directly Akka Persistence nor migrated to Akka Persistence compatible journals. Journal migration tools may
be provided in the future but do not exist at the moment.

Extensions
==========

Eventsourced and Akka Persistence are both :ref:`extending-akka-scala`.

**Eventsourced:** ``EventsourcingExtension``

- Must be explicitly created with an actor system and an application-defined journal actor as arguments.
  (see `example usage <https://github.com/eligosource/eventsourced#step-1-eventsourcingextension-initialization>`_).
- `Processors <https://github.com/eligosource/eventsourced#processor>`_ and
  `Channels <https://github.com/eligosource/eventsourced#channel>`_
  must be created with the factory methods ``processorOf`` and ``channelOf`` defined on ``EventsourcingExtension``.
- Is a central registry of created processors and channels.

**Akka Persistence:** ``Persistence`` extension

- Must **not** be explicitly created by an application. A ``Persistence`` extension is implicitly created upon first
  `PersistentActor`` creation. Journal actors are automatically created from a journal plugin configuration (see
  :ref:`journal-plugin-api`).
- ``PersistentActor``  can be created like any other actor with ``actorOf`` without using the
  ``Persistence`` extension.
- Is **not** a central registry of persistent actors.

Processors / PersistentActor
============================

**Eventsourced:** ``Eventsourced``

- Stackable trait that adds journaling (write-ahead-logging) to actors (see processor
  `definition <https://github.com/eligosource/eventsourced#step-2-event-sourced-actor-definition>`_ and
  `creation <https://github.com/eligosource/eventsourced#step-3-event-sourced-actor-creation-and-recovery>`_).
  Name ``Eventsourced`` caused some confusion in the past as many examples used ``Eventsourced`` processors
  for *command sourcing*. See also
  `this FAQ entry <https://github.com/eligosource/eventsourced/wiki/FAQ#wiki-event-sourcing-comparison>`_ for
  clarification.
- Must be explicitly `recovered <https://github.com/eligosource/eventsourced#recovery>`_ using recovery methods
  on  ``EventsourcingExtension``. Applications are responsible for avoiding an interference of replayed messages
  and new messages i.e. applications have to explicitly wait for recovery to complete. Recovery on processor
  re-start is not supported out-of-the box.
- Journaling-preserving `behavior changes <https://github.com/eligosource/eventsourced#behavior-changes>`_ are
  only possible with special-purpose methods ``become`` and ``unbecome``, in addition to non-journaling-preserving
  behavior changes with default methods ``context.become`` and ``context.unbecome``.
- Writes messages of type ``Message`` to the journal (see processor
  `usage <https://github.com/eligosource/eventsourced#step-4-event-sourced-actor-usage>`_).
  `Sender references <https://github.com/eligosource/eventsourced#sender-references>`_
  are not stored in the journal i.e. the sender reference of a replayed message is always ``system.deadLetters``.
- Supports `snapshots <https://github.com/eligosource/eventsourced#snapshots>`_.
- Identifiers are of type ``Int`` and must be application-defined.
- Does not support batch-writes of messages to the journal.
- Does not support stashing of messages.

**Akka Persistence:** ``PersistentActor``

- Trait that adds journaling to actors (see :ref:`event-sourcing`) and used by applications for
  *event sourcing* or *command sourcing*. Corresponds to ``Eventsourced`` processors in Eventsourced but is not a stackable trait.
- Automatically recovers on start and re-start, by default. :ref:`recovery` can be customized or turned off by
  overriding actor life cycle hooks ``preStart`` and ``preRestart``. ``Processor`` takes care that new messages
  never interfere with replayed messages. New messages are internally buffered until recovery completes.
- No special-purpose behavior change methods. Default behavior change methods ``context.become`` and
  ``context.unbecome`` can be used and are journaling-preserving.
- Sender references are written to the journal. Sender references of type ``PromiseActorRef`` are
  not journaled, they are ``system.deadLetters`` on replay.
- Supports :ref:`snapshots`.
- :ref:`persistence-identifiers` are of type ``String``, have a default value and can be overridden by applications.
- Supports :ref:`batch-writes`.
- Supports stashing of messages.

Channels
========

**Eventsourced:** ``DefaultChannel``

- Prevents redundant delivery of messages to a destination (see
  `usage example <https://github.com/eligosource/eventsourced#step-5-channel-usage>`_ and
  `default channel <https://github.com/eligosource/eventsourced#defaultchannel>`_).
- Is associated with a single destination actor reference. A new incarnation of the destination is not automatically
  resolved by the channel. In this case a new channel must be created.
- Must be explicitly activated using methods ``deliver`` or ``recover`` defined on ``EventsourcingExtension``.
- Must be activated **after** all processors have been activated. Depending on the
  `recovery <https://github.com/eligosource/eventsourced#recovery>`_ method, this is either done automatically or must
  be followed by the application (see `non-blocking recovery <https://github.com/eligosource/eventsourced#non-blocking-recovery>`_).
  This is necessary for a network of processors and channels to recover consistently.
- Does not redeliver messages on missing or negative delivery confirmation.
- Cannot be used standalone.

**Eventsourced:** ``ReliableChannel``

- Provides ``DefaultChannel`` functionality plus persistence and recovery from sender JVM crashes (see `ReliableChannel
  <https://github.com/eligosource/eventsourced#reliablechannel>`_). Also provides message redelivery in case of missing
  or negative delivery confirmations.
- Delivers next message to a destination only if previous message has been successfully delivered (flow control is
  done by destination).
- Stops itself when the maximum number of redelivery attempts has been reached.
- Cannot reply on persistence.
- Can be used standalone.

**Akka Persistence:** ``AtLeastOnceDelivery``

- ``AtLeastOnceDelivery`` trait is mixed in to a ``PersistentActor``
- Does not prevent redundant delivery of messages to a destination
- Is not associated with a single destination. A destination can be specified with each ``deliver`` request and is
  referred to by an actor path. A destination path is resolved to the current destination incarnation during delivery
  (via ``actorSelection``).
- Redelivers messages on missing delivery confirmation. In contrast to Eventsourced, Akka
  Persistence doesn't distinguish between missing and negative confirmations. It only has a notion of missing
  confirmations using timeouts (which are closely related to negative confirmations as both trigger message
  redelivery).

Views
=====

**Eventsourced:**

- No direct support for views. Only way to maintain a view is to use a channel and a processor as destination.

**Akka Persistence:** ``View``

- Receives the message stream written by a ``PersistentActor`` by reading it directly from the
  journal (see :ref:`persistent-views`). Alternative to using channels. Useful in situations where actors shall receive a
  persistent message stream in correct order without duplicates.
- Supports :ref:`snapshots`.

Serializers
===========

**Eventsourced:**

- Uses a protobuf serializer for serializing ``Message`` objects.
- Delegates to a configurable Akka serializer for serializing ``Message`` payloads.
- Delegates to a configurable, proprietary (stream) serializer for serializing snapshots.
- See `Serialization <https://github.com/eligosource/eventsourced#serialization>`_.

**Akka Persistence:**

- Uses a protobuf serializer for serializing ``Persistent`` objects.
- Delegates to a configurable Akka serializer for serializing ``Persistent`` payloads.
- Delegates to a configurable Akka serializer for serializing snapshots.
- See :ref:`custom-serialization`.

Sequence numbers
================

**Eventsourced:**

- Generated on a per-journal basis.

**Akka Persistence:**

- Generated on a per persistent actor basis.

Storage plugins
===============

**Eventsourced:**

- Plugin API:
  `SynchronousWriteReplaySupport <http://eligosource.github.io/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.common.support.SynchronousWriteReplaySupport>`_ and
  `AsynchronousWriteReplaySupport <http://eligosource.github.io/eventsourced/api/snapshot/#org.eligosource.eventsourced.journal.common.support.AsynchronousWriteReplaySupport>`_
- No separation between journal and snapshot storage plugins.
- All plugins pre-packaged with project (see `journals <https://github.com/eligosource/eventsourced#journals>`_ and
  `snapshot configuration <https://github.com/eligosource/eventsourced#configuration>`_)

**Akka Persistence:**

- Plugin API: ``SyncWriteJournal``, ``AsyncWriteJournal``, ``AsyncRecovery``, ``SnapshotStore``
  (see :ref:`storage-plugins`).
- Clear separation between journal and snapshot storage plugins.
- Limited number of :ref:`pre-packaged-plugins` (LevelDB journal and local snapshot store).
- Impressive list of `community plugins <http://akka.io/community/>`_.
