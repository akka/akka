.. _persistence:

###########
Persistence
###########

Akka persistence enables stateful actors to persist their internal state so that it can be recovered when an actor
is started, restarted by a supervisor or migrated in a cluster. It also allows stateful actors to recover from JVM
crashes, for example. The key concept behind Akka persistence is that only changes to an actor's internal state are
persisted but never its current state directly (except for optional snapshots). These changes are only ever appended
to storage, nothing is ever mutated, which allows for very high transaction rates and efficient replication. Stateful
actors are recovered by replaying stored changes to these actors from which they can rebuild internal state. This can
be either the full history of changes or starting from a snapshot of internal actor state which can dramatically
reduce recovery times.

Storage backends for state changes and snapshots are pluggable in Akka persistence. Currently, these are written to
the local filesystem. Distributed and replicated storage, with the possibility of scaling writes, will be available
soon.

Akka persistence is inspired by the `eventsourced`_ library. It follows the same concepts and architecture of
`eventsourced`_ but significantly differs on API and implementation level.

.. warning::

  This module is marked as **“experimental”** as of its introduction in Akka 2.3.0. We will continue to
  improve this API based on our users’ feedback, which implies that while we try to keep incompatible
  changes to a minimum the binary compatibility guarantee for maintenance releases does not apply to the
  contents of the ``akka.persistence`` package.

.. _eventsourced: https://github.com/eligosource/eventsourced

Dependencies
============

Akka persistence is a separate jar file. Make sure that you have the following dependency in your project::

  "com.typesafe.akka" %% "akka-persistence-experimental" % "@version@" @crossString@

Architecture
============

* *Processor*: A processor is a persistent, stateful actor. Messages sent to a processor are written to a journal
  before its ``receive`` method is called. When a processor is started or restarted, journaled messages are replayed
  to that processor, so that it can recover internal state from these messages.

* *Channel*: Channels are used by processors to communicate with other actors. They prevent that replayed messages
  are redundantly delivered to these actors.

* *Journal*: A journal stores the sequence of messages sent to a processor. An application can control which messages
  are stored and which are received by the processor without being journaled. The storage backend of a journal is
  pluggable.

* *Snapshot store*: A snapshot store persists snapshots of a processor's internal state. Snapshots are used for
  optimizing recovery times. The storage backend of a snapshot store is pluggable.

Configuration
=============

By default, journaled messages are written to a directory named ``journal`` in the current working directory. This
can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#journal-config

The default storage location of :ref:`snapshots` is a directory named ``snapshots`` in the current working directory.
This can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-config

.. _processors:

Processors
==========

A processor can be implemented by extending the ``Processor`` trait and implementing the ``receive`` method.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#definition

Processors only write messages of type ``Persistent`` to the journal, others are received without being persisted.
When a processor's ``receive`` method is called with a ``Persistent`` message it can safely assume that this message
has been successfully written to the journal. If a journal fails to write a ``Persistent`` message then the processor
is stopped, by default. If an application wants that a processors continues to run on persistence failures it must
handle ``PersistenceFailure`` messages. In this case, a processor may want to inform the sender about the failure,
so that the sender can re-send the message, if needed, under the assumption that the journal recovered from a
temporary failure.

A ``Processor`` itself is an ``Actor`` and can therefore be instantiated with ``actorOf``.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#usage

Recovery
--------

By default, a processor is automatically recovered on start and on restart by replaying journaled messages.
New messages sent to a processor during recovery do not interfere with replayed messages. New messages will
only be received by that processor after recovery completes.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Automated recovery on start can be disabled by overriding ``preStart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-start-disabled

In this case, a processor must be recovered explicitly by sending it a ``Recover()`` message.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-explicit

If not overridden, ``preStart`` sends a ``Recover()`` message to ``self``. Applications may also override
``preStart`` to define further ``Recover()`` parameters such as an upper sequence number bound, for example.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-start-custom

Automated recovery on restart can be disabled by overriding ``preRestart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-restart-disabled

Recovery status
^^^^^^^^^^^^^^^

A processor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recovery-status

.. _failure-handling:

Failure handling
^^^^^^^^^^^^^^^^

A persistent message that caused an exception will be received again by a processor after restart. To prevent
a replay of that message during recovery it can be marked as deleted.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#deletion

Identifiers
-----------

A processor must have an identifier that doesn't change across different actor incarnations. It defaults to the
``String`` representation of processor's path and can be obtained via the ``processorId`` method.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#processor-id

Applications can customize a processor's id by specifying an actor name during processor creation as shown in
section :ref:`processors`. This works well when using local actor references but may cause problems with remote
actor references because their paths also contain deployment information such as host and port (and actor deployments
are likely to change during the lifetime of an application). In this case, ``Processor`` implementation classes
should override ``processorId``.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#processor-id-override

Later versions of the Akka persistence module will likely offer a possibility to migrate processor ids.

Channels
========

Channels are special actors that are used by processors to communicate with other actors (channel destinations).
Channels prevent redundant delivery of replayed messages to destinations during processor recovery. A replayed
message is retained by a channel if its previous delivery has been confirmed by a destination.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-example

A channel is ready to use once it has been created, no recovery or further activation is needed. A ``Deliver``
request  instructs a channel to send a ``Persistent`` message to a destination where the sender of the ``Deliver``
request is forwarded to the destination. A processor may also reply to a message sender directly by using ``sender``
as channel destination.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-example-reply

Channel destinations confirm the delivery of a ``Persistent`` message by calling its ``confirm()`` method. This
(asynchronously) writes a confirmation entry to the journal. Replayed messages internally contain these confirmation
entries which allows a channel to decide if a message should be retained or not.

If an application crashes after a destination called ``confirm()`` but before the confirmation entry could have
been written to the journal then the unconfirmed message will be delivered again during next recovery and it is
the destination's responsibility to detect the duplicate or simply process the message again if it's an idempotent
receiver. Duplicates can be detected, for example, by tracking sequence numbers.

Currently, channels do not store ``Deliver`` requests or retry delivery on network or destination failures. This
feature (*reliable channels*) will be available soon.

Sender resolution
-----------------

``ActorRef`` s of ``Persistent`` message senders are also stored in the journal. Consequently, they may become invalid if
an application is restarted and messages are replayed. For example, the stored ``ActorRef`` may then reference
a previous incarnation of a sender and a new incarnation of that sender cannot receive a reply from a processor.
This may be acceptable for many applications but others may require that a new sender incarnation receives the
reply (to reliably resume a conversation between actors after a JVM crash, for example). Here, a channel may
assist in resolving new sender incarnations by specifying a third ``Deliver`` argument:

* ``Resolve.Destination`` if the sender of a persistent message is used as channel destination

  .. includecode:: code/docs/persistence/PersistenceDocSpec.scala#resolve-destination

* ``Resolve.Sender`` if the sender of a persistent message is forwarded to a destination.

  .. includecode:: code/docs/persistence/PersistenceDocSpec.scala#resolve-sender

Default is ``Resolve.Off`` which means no resolution. Find out more in the ``Deliver`` API docs.

Identifiers
-----------

In the same way as :ref:`processors`, channels also have an identifier that defaults to a channel's path. A channel
identifier can therefore be customized by using a custom actor name at channel creation. As already mentioned, this
works well when using local actor references but may cause problems with remote actor references. In this case, an
application-defined channel id should be provided as argument to ``Channel.props(String)``

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-id-override

Persistent messages
===================

Payload
-------

The payload of a ``Persistent`` message can be obtained via its

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/Persistent.scala#payload

method or by pattern matching

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#payload-pattern-matching

Inside processors, new persistent messages are derived from the current persistent message before sending them via a
channel, either by calling ``p.withPayload(...)`` or ``Persistent.create(...)`` where the latter uses the
implicit ``currentPersistentMessage`` made available by ``Processor``.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#current-message

This is necessary for delivery confirmations to work properly. Both ways are equivalent but we recommend
using ``p.withPayload(...)`` for clarity.

Sequence number
---------------

The sequence number of a ``Persistent`` message can be obtained via its

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/Persistent.scala#sequence-nr

method or by pattern matching

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#sequence-nr-pattern-matching

Persistent messages are assigned sequence numbers on a per-processor basis. A sequence starts at ``1L`` and
doesn't contain gaps unless a processor marks a message as deleted.

.. _snapshots:

Snapshots
=========

Snapshots can dramatically reduce recovery times. Processors can save snapshots of internal state by calling the
``saveSnapshot`` method on ``Processor``. If saving of a snapshot succeeds, the processor will receive a
``SaveSnapshotSuccess`` message, otherwise a ``SaveSnapshotFailure`` message

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#save-snapshot

where ``metadata`` is of type ``SnapshotMetadata``:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/Snapshot.scala#snapshot-metadata

During recovery, the processor is offered a previously saved snapshot via a ``SnapshotOffer`` message from
which it can initialize internal state.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#snapshot-offer

The replayed messages that follow the ``SnapshotOffer`` message, if any, are younger than the offered snapshot.
They finally recover the processor to its current (i.e. latest) state.

In general, a processor is only offered a snapshot if that processor has previously saved one or more snapshots
and at least one of these snapshots matches the ``SnapshotSelectionCriteria`` that can be specified for recovery.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#snapshot-criteria

If not specified, they default to ``SnapshotSelectionCriteria.Latest`` which selects the latest (= youngest) snapshot.
To disable snapshot-based recovery, applications should use ``SnapshotSelectionCriteria.None``. A recovery where no
saved snapshot matches the specified ``SnapshotSelectionCriteria`` will replay all journaled messages.

.. _event-sourcing:

Event sourcing
==============

In all the examples so far, messages that change a processor's state have been sent as ``Persistent`` messages
by an application, so that they can be replayed during recovery. From this point of view, the journal acts as
a write-ahead-log for whatever ``Persistent`` messages a processor receives. This is also known as *command
sourcing*. Commands, however, may fail and some applications cannot tolerate command failures during recovery.

For these applications `Event Sourcing`_ is a better choice. Applied to Akka persistence, the basic idea behind
event sourcing is quite simple. A processor receives a (non-persistent) command which is first validated if it
can be applied to the current state. Here, validation can mean anything, from simple inspection of a command
message's fields up to a conversation with several external services, for example. If validation succeeds, events
are generated from the command, representing the effect of the command. These events are then persisted and, after
successful persistence, used to change a processor's state. When the processor needs to be recovered, only the
persisted events are replayed of which we know that they can be successfully applied. In other words, events
cannot fail when being replayed to a processor, in contrast to commands. Eventsourced processors may of course
also process commands that do not change application state, such as query commands, for example.

.. _Event Sourcing: http://martinfowler.com/eaaDev/EventSourcing.html

Akka persistence supports event sourcing with the ``EventsourcedProcessor`` trait (which implements event sourcing
as a pattern on top of command sourcing). A processor that extends this trait does not handle ``Persistent`` messages
directly but uses the ``persist`` method to persist and handle events. The behavior of an ``EventsourcedProcessor``
is defined by implementing ``receiveReplay`` and ``receiveCommand``. This is best explained with an example (which
is also part of ``akka-sample-persistence``).

.. includecode:: ../../../akka-samples/akka-sample-persistence/src/main/scala/sample/persistence/EventsourcedExample.scala#eventsourced-example

The example defines two data types, ``Cmd`` and ``Evt`` to represent commands and events, respectively. The
``state`` of the ``ExampleProcessor`` is a list of persisted event data contained in ``ExampleState``.

The processor's ``receiveReplay`` method defines how ``state`` is updated during recovery by handling ``Evt``
and ``SnapshotOffer`` messages. The processor's ``receiveCommand`` method is a command handler. In this example,
a command is handled by generating two events which are then persisted and handled. Events are persisted by calling
``persist`` with an event (or a sequence of events) as first argument and an event handler as second argument.

The ``persist`` method persists events asynchronously and the event handler is executed for successfully persisted
events. Successfully persisted events are internally sent back to the processor as separate messages which trigger
the event handler execution. An event handler may therefore close over processor state and mutate it. The sender
of a persisted event is the sender of the corresponding command. This allows event handlers to reply to the sender
of a command (not shown).

The main responsibility of an event handler is changing processor state using event data and notifying others
about successful state changes by publishing events.

When persisting events with ``persist`` it is guaranteed that the processor will not receive new commands between
the ``persist`` call and the execution(s) of the associated event handler. This also holds for multiple ``persist``
calls in context of a single command.

The example also demonstrates how to change the processor's default behavior, defined by ``receiveCommand``, to
another behavior, defined by ``otherCommandHandler``, and back using ``context.become()`` and ``context.unbecome()``.
See also the API docs of ``persist`` for further details.

Batch writes
============

Applications may also send a batch of ``Persistent`` messages to a processor via a ``PersistentBatch`` message.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#batch-write

``Persistent`` messages contained in a ``PersistentBatch`` message are written to the journal atomically but are
received  by the processor separately (as ``Persistent`` messages). They are also replayed separately. Batch writes
can not only increase the throughput of a processor but may also be necessary for consistency reasons. For example,
in :ref:`event-sourcing`, all events that are generated and persisted by a single command are batch-written to the
journal. The recovery of an ``EventsourcedProcessor`` will therefore never be done partially i.e. with only a subset
of events persisted by a single command.

Storage plugins
===============

Storage backends for journals and snapshot stores are plugins in akka-persistence. The default journal plugin writes
messages to LevelDB. The default snapshot store plugin writes snapshots as individual files to the local filesystem.
Applications can provide their own plugins by implementing a plugin API and activate them by configuration. Plugin
development requires the following imports:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#plugin-imports

Journal plugin API
------------------

A journal plugin either extends ``SyncWriteJournal`` or ``AsyncWriteJournal``.  ``SyncWriteJournal`` is an
actor that should be extended when the storage backend API only supports synchronous, blocking writes. The
methods to be implemented in this case are:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/SyncWriteJournal.scala#journal-plugin-api

``AsyncWriteJournal`` is an actor that should be extended if the storage backend API supports asynchronous,
non-blocking writes. The methods to be implemented in that case are:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/AsyncWriteJournal.scala#journal-plugin-api

Message replays are always asynchronous, therefore, any journal plugin must implement:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/AsyncReplay.scala#journal-plugin-api

A journal plugin can be activated with the following minimal configuration:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#journal-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``
for ``SyncWriteJournal`` plugins and ``akka.actor.default-dispatcher`` for ``AsyncWriteJournal`` plugins.

Snapshot store plugin API
-------------------------

A snapshot store plugin must extend the ``SnapshotStore`` actor and implement the following methods:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/snapshot/SnapshotStore.scala#snapshot-store-plugin-api

A snapshot store plugin can be activated with the following minimal configuration:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-store-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``.

Custom serialization
====================

Serialization of snapshots and payloads of ``Persistent`` messages is configurable with Akka's
:ref:`serialization-scala` infrastructure. For example, if an application wants to serialize

* payloads of type ``MyPayload`` with a custom ``MyPayloadSerializer`` and
* snapshots of type ``MySnapshot`` with a custom ``MySnapshotSerializer``

it must add

.. includecode:: code/docs/persistence/PersistenceSerializerDocSpec.scala#custom-serializer-config

to the application configuration. If not specified, a default serializer is used, which is the ``JavaSerializer``
in this example.

Miscellaneous
=============

State machines
--------------

State machines can be persisted by mixing in the ``FSM`` trait into processors.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#fsm-example

Upcoming features
=================

* Reliable channels
* Extended deletion of messages and snapshots
* ...
