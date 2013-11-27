.. _persistence-java:

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

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-persistence-experimental_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Architecture
============

* *Processor*: A processor is a persistent, stateful actor. Messages sent to a processor are written to a journal
  before its ``onReceive`` method is called. When a processor is started or restarted, journaled messages are replayed
  to that processor, so that it can recover internal state from these messages.

* *Channel*: Channels are used by processors to communicate with other actors. They prevent that replayed messages
  are redundantly delivered to these actors.

* *Journal*: A journal stores the sequence of messages sent to a processor. An application can control which messages
  are stored and which are received by the processor without being journaled. The storage backend of a journal is
  pluggable.

* *Snapshot store*: A snapshot store persists snapshots of a processor's internal state. Snapshots are used for
  optimizing recovery times. The storage backend of a snapshot store is pluggable.

* *Event sourcing*. Based on the building blocks described above, Akka persistence provides abstractions for the
  development of event sourced applications (see section :ref:`event-sourcing-java`)

Configuration
=============

By default, journaled messages are written to a directory named ``journal`` in the current working directory. This
can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#journal-config

The default storage location of :ref:`snapshots-java` is a directory named ``snapshots`` in the current working directory.
This can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-config

.. _processors-java:

Processors
==========

A processor can be implemented by extending the abstract ``UntypedProcessor`` class and implementing the
``onReceive`` method.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#definition

Processors only write messages of type ``Persistent`` to the journal, others are received without being persisted.
When a processor's ``onReceive`` method is called with a ``Persistent`` message it can safely assume that this message
has been successfully written to the journal. If a journal fails to write a ``Persistent`` message then the processor
is stopped, by default. If an application wants that a processors continues to run on persistence failures it must
handle ``PersistenceFailure`` messages. In this case, a processor may want to inform the sender about the failure,
so that the sender can re-send the message, if needed, under the assumption that the journal recovered from a
temporary failure.

An ``UntypedProcessor`` itself is an ``Actor`` and can therefore be instantiated with ``actorOf``.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#usage

Recovery
--------

By default, a processor is automatically recovered on start and on restart by replaying persistent messages.
New messages sent to a processor during recovery do not interfere with replayed messages. New messages will
only be received by that processor after recovery completes.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Automated recovery on start can be disabled by overriding ``preStart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-disabled

In this case, a processor must be recovered explicitly by sending it a ``Recover`` message.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-explicit

If not overridden, ``preStart`` sends a ``Recover`` message to ``getSelf()``. Applications may also override
``preStart`` to define further ``Recover`` parameters such as an upper sequence number bound, for example.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-custom

Upper sequence number bounds can be used to recover a processor to past state instead of current state. Automated
recovery on restart can be disabled by overriding ``preRestart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-restart-disabled

Recovery status
^^^^^^^^^^^^^^^

A processor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recovery-status

.. _failure-handling-java:

Failure handling
^^^^^^^^^^^^^^^^

A persistent message that caused an exception will be received again by a processor after restart. To prevent
a replay of that message during recovery it can be deleted.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#deletion

Message deletion
----------------

A processor can delete a single message by calling the ``deleteMessage`` method with the sequence number of
that message as argument. An optional ``permanent`` parameter specifies whether the message shall be permanently
deleted from the journal or only marked as deleted. In both cases, the message won't be replayed. Later extensions
to Akka persistence will allow to replay messages that have been marked as deleted which can be useful for debugging
purposes, for example. To delete all messages (journaled by a single processor) up to a specified sequence number,
processors can call the ``deleteMessages`` method.

Identifiers
-----------

A processor must have an identifier that doesn't change across different actor incarnations. It defaults to the
``String`` representation of processor's path and can be obtained via the ``processorId`` method.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#processor-id

Applications can customize a processor's id by specifying an actor name during processor creation as shown in
section :ref:`processors-java`. This works well when using local actor references but may cause problems with remote
actor references because their paths also contain deployment information such as host and port (and actor deployments
are likely to change during the lifetime of an application). In this case, ``UntypedProcessor`` implementation classes
should override ``processorId``.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#processor-id-override

Later versions of Akka persistence will likely offer a possibility to migrate processor ids.

Channels
========

Channels are special actors that are used by processors to communicate with other actors (channel destinations).
Channels prevent redundant delivery of replayed messages to destinations during processor recovery. A replayed
message is retained by a channel if its previous delivery has been confirmed by a destination.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#channel-example

A channel is ready to use once it has been created, no recovery or further activation is needed. A ``Deliver``
request  instructs a channel to send a ``Persistent`` message to a destination where the sender of the ``Deliver``
request is forwarded to the destination. A processor may also reply to a message sender directly by using
``getSender()`` as channel destination (not shown).

.. includecode:: code/docs/persistence/PersistenceDocTest.java#channel-example-reply

Persistent messages delivered by a channel are of type ``ConfirmablePersistent``. It extends ``Persistent`` and
adds a ``confirm()`` method. Channel destinations confirm the delivery of a ``ConfirmablePersistent`` message by
calling ``confirm()``. This (asynchronously) writes a confirmation entry to the journal. Replayed messages
internally contain these confirmation entries which allows a channel to decide if a message should be retained or
not. ``ConfirmablePersistent`` messages can be used whereever ``Persistent`` messages are expected, which allows
processors to be used as channel destinations, for example.

Message re-delivery
-------------------

If an application crashes after a destination called ``confirm()`` but before the confirmation entry could have
been written to the journal then the unconfirmed message will be re-delivered during next recovery of the sending
processor. It is the destination's responsibility to detect the duplicate or simply process the message again if
it's an idempotent receiver. Duplicates can be detected, for example, by tracking sequence numbers.

Although a channel prevents message loss in case of sender (JVM) crashes it doesn't attempt re-deliveries if a
destination is unavailable. To achieve reliable communication with a (remote) target, a channel destination may
want to use the :ref:`reliable-proxy` or add the message to a queue that is managed by a third party message
broker, for example. In latter case, the channel destination will first add the received message to the queue
and then call ``confirm()`` on the received ``ConfirmablePersistent`` message.

Persistent channels
-------------------

Channels created with ``Channel.props`` do not persist messages. This is not necessary because these (transient)
channels shall only be used in combination with a sending processor that takes care of message persistence.

However, if an application wants to use a channel standalone (without a sending processor), to prevent message
loss in case of a sender (JVM) crash, it should use a persistent channel which can be created with ``PersistentChannel.props``.
A persistent channel additionally persists messages before they are delivered. Persistence is achieved by an
internal processor that delegates delivery to a transient channel. A persistent channel, when used standalone,
can therefore provide the same message re-delivery semantics as a transient channel in combination with an
application-defined processor.

  .. includecode:: code/docs/persistence/PersistenceDocTest.java#persistent-channel-example

By default, a persistent channel doesn't reply whether a ``Persistent`` message, sent with ``Deliver``, has been
successfully persisted or not. This can be enabled by creating the channel with the ``persistentReply`` parameter
set to ``true``: ``PersistentChannel.props(true)``. With this setting, either the successfully persisted message
is replied to the sender or a ``PersistenceFailure``. In case of a persistence failure, the sender should re-send
the message.

Using a persistent channel in combination with an application-defined processor can make sense if destinations are
unavailable for a long time and an application doesn't want to buffer all messages in memory (but write them to the
journal instead). In this case, delivery can be disabled with ``DisableDelivery`` (to stop delivery and persist-only)
and re-enabled with ``EnableDelivery``. A disabled channel that receives ``EnableDelivery`` will restart itself and
re-deliver all persisted, unconfirmed messages before serving new ``Deliver`` requests.

Sender resolution
-----------------

``ActorRef`` s of ``Persistent`` message senders are also stored in the journal. Consequently, they may become invalid if
an application is restarted and messages are replayed. For example, the stored ``ActorRef`` may then reference
a previous incarnation of a sender and a new incarnation of that sender cannot receive a reply from a processor.
This may be acceptable for many applications but others may require that a new sender incarnation receives the
reply (to reliably resume a conversation between actors after a JVM crash, for example). Here, a channel may
assist in resolving new sender incarnations by specifying a third ``Deliver`` argument:

* ``Resolve.destination()`` if the sender of a persistent message is used as channel destination

  .. includecode:: code/docs/persistence/PersistenceDocTest.java#resolve-destination

* ``Resolve.sender()`` if the sender of a persistent message is forwarded to a destination.

  .. includecode:: code/docs/persistence/PersistenceDocTest.java#resolve-sender

Default is ``Resolve.off()`` which means no resolution. Find out more in the ``Deliver`` API docs.

Identifiers
-----------

In the same way as :ref:`processors-java`, channels also have an identifier that defaults to a channel's path. A channel
identifier can therefore be customized by using a custom actor name at channel creation. As already mentioned, this
works well when using local actor references but may cause problems with remote actor references. In this case, an
application-defined channel id should be provided as argument to ``Channel.props(String)`` or
``PersistentChannel.props(String)``.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#channel-id-override

Persistent messages
===================

Payload
-------

The payload of a ``Persistent`` message can be obtained via its ``payload`` method. Inside processors, new messages
must be derived from the current persistent message before sending them via a channel, either by calling ``p.withPayload(...)``
or ``Persistent.create(..., getCurrentPersistentMessage())`` where ``getCurrentPersistentMessage()`` is defined on
``UntypedProcessor``.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#current-message

This is necessary for delivery confirmations to work properly. Both
ways are equivalent but we recommend using ``p.withPayload(...)`` for clarity. It is not allowed to send a message
via a channel that has been created with ``Persistent.create(...)``. This would redeliver the message on every replay
even though its delivery was confirmed by a destination.

Sequence number
---------------

The sequence number of a ``Persistent`` message can be obtained via its ``sequenceNr`` method. Persistent
messages are assigned sequence numbers on a per-processor basis (or per persistent channel basis if used
standalone). A sequence starts at ``1L`` and doesn't contain gaps unless a processor deletes a message.

.. _snapshots-java:

Snapshots
=========

Snapshots can dramatically reduce recovery times. Processors can save snapshots of internal state by calling the
``saveSnapshot`` method on ``Processor``. If saving of a snapshot succeeds, the processor will receive a
``SaveSnapshotSuccess`` message, otherwise a ``SaveSnapshotFailure`` message.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#save-snapshot

During recovery, the processor is offered a previously saved snapshot via a ``SnapshotOffer`` message from
which it can initialize internal state.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#snapshot-offer

The replayed messages that follow the ``SnapshotOffer`` message, if any, are younger than the offered snapshot.
They finally recover the processor to its current (i.e. latest) state.

In general, a processor is only offered a snapshot if that processor has previously saved one or more snapshots
and at least one of these snapshots matches the ``SnapshotSelectionCriteria`` that can be specified for recovery.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#snapshot-criteria

If not specified, they default to ``SnapshotSelectionCriteria.latest()`` which selects the latest (= youngest) snapshot.
To disable snapshot-based recovery, applications should use ``SnapshotSelectionCriteria.none()``. A recovery where no
saved snapshot matches the specified ``SnapshotSelectionCriteria`` will replay all journaled messages.

Snapshot deletion
-----------------

A processor can delete a single snapshot by calling the ``deleteSnapshot`` method with the sequence number and the
timestamp of the snapshot as argument. To bulk-delete snapshots that match a specified ``SnapshotSelectionCriteria``
argument, processors can call the ``deleteSnapshots`` method.

.. _event-sourcing-java:

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

Akka persistence supports event sourcing with the abstract ``UntypedEventsourcedProcessor`` class (which implements
event sourcing as a pattern on top of command sourcing). A processor that extends this abstract class does not handle
``Persistent`` messages directly but uses the ``persist`` method to persist and handle events. The behavior of an
``UntypedEventsourcedProcessor`` is defined by implementing ``onReceiveReplay`` and ``onReceiveCommand``. This is
best explained with an example (which is also part of ``akka-sample-persistence``).

.. includecode:: ../../../akka-samples/akka-sample-persistence/src/main/java/sample/persistence/japi/EventsourcedExample.java#eventsourced-example

The example defines two data types, ``Cmd`` and ``Evt`` to represent commands and events, respectively. The
``state`` of the ``ExampleProcessor`` is a list of persisted event data contained in ``ExampleState``.

The processor's ``onReceiveReplay`` method defines how ``state`` is updated during recovery by handling ``Evt``
and ``SnapshotOffer`` messages. The processor's ``onReceiveCommand`` method is a command handler. In this example,
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

The example also demonstrates how to change the processor's default behavior, defined by ``onReceiveCommand``, to
another behavior, defined by ``otherCommandHandler``, and back using ``getContext().become()`` and
``getContext().unbecome()``. See also the API docs of ``persist`` for further details.

Batch writes
============

To optimize throughput, an ``UntypedProcessor`` internally batches received ``Persistent`` messages under high load before
writing them to the journal (as a single batch). The batch size dynamically grows from 1 under low and moderate loads
to a configurable maximum size (default is ``200``) under high load.

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#max-batch-size

A new batch write is triggered by a processor as soon as a batch reaches the maximum size or if the journal completed
writing the previous batch. Batch writes are never timer-based which keeps latencies as low as possible.

Applications that want to have more explicit control over batch writes and batch sizes can send processors
``PersistentBatch`` messages.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#batch-write

``Persistent`` messages contained in a ``PersistentBatch`` message are always written atomically, even if the batch
size is greater than ``max-batch-size``. Also, a ``PersistentBatch`` is written isolated from other batches.
``Persistent`` messages contained in a ``PersistentBatch`` are received individually by a processor.

``PersistentBatch`` messages, for example, are used internally by an ``UntypedEventsourcedProcessor`` to ensure atomic
writes of events. All events that are persisted in context of a single command are written as single batch to the
journal (even if ``persist`` is called multiple times per command). The recovery of an ``UntypedEventsourcedProcessor``
will therefore never be done partially i.e. with only a subset of events persisted by a single command.

Storage plugins
===============

Storage backends for journals and snapshot stores are plugins in akka-persistence. The default journal plugin writes
messages to LevelDB. The default snapshot store plugin writes snapshots as individual files to the local filesystem.
Applications can provide their own plugins by implementing a plugin API and activate them by configuration. Plugin
development requires the following imports:

.. includecode:: code/docs/persistence/PersistencePluginDocTest.java#plugin-imports

Journal plugin API
------------------

A journal plugin either extends ``SyncWriteJournal`` or ``AsyncWriteJournal``.  ``SyncWriteJournal`` is an
actor that should be extended when the storage backend API only supports synchronous, blocking writes. The
methods to be implemented in this case are:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/SyncWritePlugin.java#sync-write-plugin-api

``AsyncWriteJournal`` is an actor that should be extended if the storage backend API supports asynchronous,
non-blocking writes. The methods to be implemented in that case are:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncWritePlugin.java#async-write-plugin-api

Message replays are always asynchronous, therefore, any journal plugin must implement:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncReplayPlugin.java#async-replay-plugin-api

A journal plugin can be activated with the following minimal configuration:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#journal-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``
for ``SyncWriteJournal`` plugins and ``akka.actor.default-dispatcher`` for ``AsyncWriteJournal`` plugins.

Snapshot store plugin API
-------------------------

A snapshot store plugin must extend the ``SnapshotStore`` actor and implement the following methods:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/snapshot/japi/SnapshotStorePlugin.java#snapshot-store-plugin-api

A snapshot store plugin can be activated with the following minimal configuration:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-store-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``.

Custom serialization
====================

Serialization of snapshots and payloads of ``Persistent`` messages is configurable with Akka's
:ref:`serialization-java` infrastructure. For example, if an application wants to serialize

* payloads of type ``MyPayload`` with a custom ``MyPayloadSerializer`` and
* snapshots of type ``MySnapshot`` with a custom ``MySnapshotSerializer``

it must add

.. includecode:: ../scala/code/docs/persistence/PersistenceSerializerDocSpec.scala#custom-serializer-config

to the application configuration. If not specified, a default serializer is used, which is the ``JavaSerializer``
in this example.
