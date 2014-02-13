.. _persistence-scala:

###########
Persistence
###########

Akka persistence enables stateful actors to persist their internal state so that it can be recovered when an actor
is started, restarted after a JVM crash or by a supervisor, or migrated in a cluster. The key concept behind Akka
persistence is that only changes to an actor's internal state are persisted but never its current state directly
(except for optional snapshots). These changes are only ever appended to storage, nothing is ever mutated, which
allows for very high transaction rates and efficient replication. Stateful actors are recovered by replaying stored
changes to these actors from which they can rebuild internal state. This can be either the full history of changes
or starting from a snapshot which can dramatically reduce recovery times. Akka persistence also provides point-to-point
communication channels with at-least-once message delivery semantics.

.. warning::

  This module is marked as **“experimental”** as of its introduction in Akka 2.3.0. We will continue to
  improve this API based on our users’ feedback, which implies that while we try to keep incompatible
  changes to a minimum the binary compatibility guarantee for maintenance releases does not apply to the
  contents of the ``akka.persistence`` package.

Akka persistence is inspired by and the official replacement of the `eventsourced`_ library. It follows the same
concepts and architecture of `eventsourced`_ but significantly differs on API and implementation level.

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

* *View*: A view is a persistent, stateful actor that receives journaled messages that have been written by another
  processor. A view itself does not journal new messages, instead, it updates internal state only from a processor's
  replicated message stream.

* *Channel*: Channels are used by processors and views to communicate with other actors. They prevent that replayed
  messages are redundantly delivered to these actors and provide at-least-once message delivery semantics, also in
  case of sender and receiver JVM crashes.

* *Journal*: A journal stores the sequence of messages sent to a processor. An application can control which messages
  are journaled and which are received by the processor without being journaled. The storage backend of a journal is
  pluggable. The default journal storage plugin writes to the local filesystem, replicated journals are available as
  `Community plugins`_.

* *Snapshot store*: A snapshot store persists snapshots of a processor's or a view's internal state. Snapshots are
  used for optimizing recovery times. The storage backend of a snapshot store is pluggable. The default snapshot
  storage plugin writes to the local filesystem.

* *Event sourcing*. Based on the building blocks described above, Akka persistence provides abstractions for the
  development of event sourced applications (see section :ref:`event-sourcing`)

.. _Community plugins: https://gist.github.com/krasserm/8612920#file-akka-persistence-plugins-md

.. _processors:

Processors
==========

A processor can be implemented by extending the ``Processor`` trait and implementing the ``receive`` method.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#definition

Processors only write messages of type ``Persistent`` to the journal, others are received without being persisted.
When a processor's ``receive`` method is called with a ``Persistent`` message it can safely assume that this message
has been successfully written to the journal. If a journal fails to write a ``Persistent`` message then the processor
is stopped, by default. If a processor should continue running on persistence failures it must handle
``PersistenceFailure`` messages. In this case, a processor may want to inform the sender about the failure,
so that the sender can re-send the message, if needed.

A ``Processor`` itself is an ``Actor`` and can therefore be instantiated with ``actorOf``.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#usage

Recovery
--------

By default, a processor is automatically recovered on start and on restart by replaying journaled messages.
New messages sent to a processor during recovery do not interfere with replayed messages. New messages will
only be received by a processor after recovery completes.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Automated recovery on start can be disabled by overriding ``preStart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-start-disabled

In this case, a processor must be recovered explicitly by sending it a ``Recover()`` message.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-explicit

If not overridden, ``preStart`` sends a ``Recover()`` message to ``self``. Applications may also override
``preStart`` to define further ``Recover()`` parameters such as an upper sequence number bound, for example.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-start-custom

Upper sequence number bounds can be used to recover a processor to past state instead of current state. Automated
recovery on restart can be disabled by overriding ``preRestart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recover-on-restart-disabled

Recovery status
^^^^^^^^^^^^^^^

A processor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#recovery-status

.. _failure-handling:

Failure handling
^^^^^^^^^^^^^^^^

A persistent message that caused an exception will be received again by a processor after restart. To prevent
a replay of that message during recovery it can be deleted.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#deletion

Message deletion
----------------

A processor can delete a single message by calling the ``deleteMessage`` method with the sequence number of
that message as argument. An optional ``permanent`` parameter specifies whether the message shall be permanently
deleted from the journal or only marked as deleted. In both cases, the message won't be replayed. Later extensions
to Akka persistence will allow to replay messages that have been marked as deleted which can be useful for debugging
purposes, for example. To delete all messages (journaled by a single processor) up to a specified sequence number,
processors should call the ``deleteMessages`` method.

Identifiers
-----------

A processor must have an identifier that doesn't change across different actor incarnations. It defaults to the
``String`` representation of processor's path without the address part and can be obtained via the ``processorId``
method.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#processor-id

Applications can customize a processor's id by specifying an actor name during processor creation as shown in
section :ref:`processors`. This changes that processor's name in its actor hierarchy and hence influences only
part of the processor id. To fully customize a processor's id, the ``processorId`` method must be overridden.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#processor-id-override

Overriding ``processorId`` is the recommended way to generate stable identifiers.

.. _views:

Views
=====

Views can be implemented by extending the ``View`` trait  and implementing the ``receive`` and the ``processorId``
methods.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#view

The ``processorId`` identifies the processor from which the view receives journaled messages. It is not necessary
the referenced processor is actually running. Views read messages from a processor's journal directly. When a
processor is started later and begins to write new messages, the corresponding view is updated automatically, by
default.

Updates
-------

The default update interval of all views of an actor system is configurable:

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#auto-update-interval

``View`` implementation classes may also override the ``autoUpdateInterval`` method to return a custom update
interval for a specific view class or view instance. Applications may also trigger additional updates at
any time by sending a view an ``Update`` message.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#view-update

If the ``await`` parameter is set to ``true``, messages that follow the ``Update`` request are processed when the
incremental message replay, triggered by that update request, completed. If set to ``false`` (default), messages
following the update request may interleave with the replayed message stream. Automated updates always run with
``await = false``.

Automated updates of all views of an actor system can be turned off by configuration:

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#auto-update

Implementation classes may override the configured default value by overriding the ``autoUpdate`` method. To
limit the number of replayed messages per update request, applications can configure a custom
``akka.persistence.view.auto-update-replay-max`` value or override the ``autoUpdateReplayMax`` method. The number
of replayed messages for manual updates can be limited with the ``replayMax`` parameter of the ``Update`` message.

Recovery
--------

Initial recovery of views works in the very same way as for :ref:`processors` (i.e. by sending a ``Recover`` message
to self). The maximum number of replayed messages during initial recovery is determined by ``autoUpdateReplayMax``.
Further possibilities to customize initial recovery are explained in section :ref:`processors`.

Identifiers
-----------

A view must have an identifier that doesn't change across different actor incarnations. It defaults to the
``String`` representation of the actor path without the address part and can be obtained via the ``viewId``
method.

Applications can customize a view's id by specifying an actor name during view creation. This changes that view's
name in its actor hierarchy and hence influences only part of the view id. To fully customize a view's id, the
``viewId`` method must be overridden. Overriding ``viewId`` is the recommended way to generate stable identifiers.

The ``viewId`` must differ from the referenced ``processorId``, unless :ref:`snapshots` of a view and its
processor shall be shared (which is what applications usually do not want).

.. _channels:

Channels
========

Channels are special actors that are used by processors or views to communicate with other actors (channel
destinations). The following discusses channels in context of processors but this is also applicable to views.

Channels prevent redundant delivery of replayed messages to destinations during processor recovery. A replayed
message is retained by a channel if its delivery has been confirmed by a destination.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-example

A channel is ready to use once it has been created, no recovery or further activation is needed. A ``Deliver``
request  instructs a channel to send a ``Persistent`` message to a destination. A destination is provided as
``ActorPath`` and messages are sent by the channel via that path's ``ActorSelection``. Sender references are
preserved by a channel, therefore, a destination can reply to the sender of a ``Deliver`` request.

If a processor wants to reply to a ``Persistent`` message sender it should use the ``sender`` path as channel
destination.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-example-reply

Persistent messages delivered by a channel are of type ``ConfirmablePersistent``. ``ConfirmablePersistent`` extends
``Persistent`` by adding the methods ``confirm`` and ``redeliveries`` (see also :ref:`redelivery`). A channel
destination confirms the delivery of a ``ConfirmablePersistent`` message by calling ``confirm()`` on that message.
This asynchronously writes a confirmation entry to the journal. Replayed messages internally contain confirmation
entries which allows a channel to decide if it should retain these messages or not.

A ``Processor`` can also be used as channel destination i.e. it can persist ``ConfirmablePersistent`` messages too.

.. _redelivery:

Message re-delivery
-------------------

Channels re-deliver messages to destinations if they do not confirm delivery within a configurable timeout.
This timeout can be specified as ``redeliverInterval`` when creating a channel, optionally together with the
maximum number of re-deliveries a channel should attempt for each unconfirmed message. The number of re-delivery
attempts can be obtained via the ``redeliveries`` method on ``ConfirmablePersistent`` or by pattern matching.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-custom-settings

A channel keeps messages in memory until their successful delivery has been confirmed or the maximum number of
re-deliveries is reached. To be notified about messages that have reached the maximum number of re-deliveries,
applications can register a listener at channel creation.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#channel-custom-listener

A listener receives ``RedeliverFailure`` notifications containing all messages that could not be delivered. On
receiving a ``RedeliverFailure`` message, an application may decide to restart the sending processor to enforce
a re-send of these messages to the channel or confirm these messages to prevent further re-sends. The sending
processor can also be restarted any time later to re-send unconfirmed messages.

This combination of

* message persistence by sending processors
* message replays by sending processors
* message re-deliveries by channels and
* application-level confirmations (acknowledgements) by destinations

enables channels to provide at-least-once message delivery semantics. Possible duplicates can be detected by
destinations by tracking message sequence numbers. Message sequence numbers are generated per sending processor.
Depending on how a processor routes outbound messages to destinations, they may either see a contiguous message
sequence or a sequence with gaps.

.. warning::

  If a processor emits more than one outbound message per inbound ``Persistent`` message it **must** use a
  separate channel for each outbound message to ensure that confirmations are uniquely identifiable, otherwise,
  at-least-once message delivery semantics do not apply. This rule has been introduced to avoid writing additional
  outbound message identifiers to the journal which would decrease the overall throughput. It is furthermore
  recommended to collapse multiple outbound messages to the same destination into a single outbound message,
  otherwise, if sent via multiple channels, their ordering is not defined.

If an application wants to have more control how sequence numbers are assigned to messages it should use an
application-specific sequence number generator and include the generated sequence numbers into the ``payload``
of ``Persistent`` messages.

Persistent channels
-------------------

Channels created with ``Channel.props`` do not persist messages. These channels are usually used in combination
with a sending processor that takes care of persistence, hence, channel-specific persistence is not necessary in
this case. They are referred to as transient channels in the following.

Persistent channels are like transient channels but additionally persist messages before delivering them. Messages
that have been persisted by a persistent channel are deleted when destinations confirm their delivery. A persistent
channel can be created with ``PersistentChannel.props`` and configured with a ``PersistentChannelSettings`` object.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#persistent-channel-example

A persistent channel is useful for delivery of messages to slow destinations or destinations that are unavailable
for a long time. It can constrain the number of pending confirmations based on the ``pendingConfirmationsMax``
and ``pendingConfirmationsMin`` parameters of ``PersistentChannelSettings``.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#persistent-channel-watermarks

It suspends delivery when the number of pending confirmations reaches ``pendingConfirmationsMax`` and resumes
delivery again when this number falls below ``pendingConfirmationsMin``. This prevents both, flooding destinations
with more messages than they can process and unlimited memory consumption by the channel. A persistent channel
continues to persist new messages even when message delivery is temporarily suspended.

Standalone usage
----------------

Applications may also use channels standalone. Transient channels can be used standalone if re-delivery attempts
to destinations are required but message loss in case of a sender JVM crash is not an issue. If message loss in
case of a sender JVM crash is an issue, persistent channels should be used. In this case, applications may want to
receive replies from the channel whether messages have been successfully persisted or not. This can be enabled by
creating the channel with the ``replyPersistent`` configuration parameter set to ``true``:

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#persistent-channel-reply

With this setting, either the successfully persisted message is replied to the sender or a ``PersistenceFailure``
message. In case the latter case, the sender should re-send the message.

Identifiers
-----------

In the same way as :ref:`processors` and :ref:`views`, channels also have an identifier that defaults to a channel's
path. A channel identifier can therefore be customized by using a custom actor name at channel creation. This changes
that channel's name in its actor hierarchy and hence influences only part of the channel identifier. To fully customize
a channel identifier, it should be provided as argument ``Channel.props(String)`` or ``PersistentChannel.props(String)``
(recommended to generate stable identifiers).

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
channel, either by calling ``p.withPayload(...)`` or ``Persistent(...)`` where the latter uses the
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

Persistent messages are assigned sequence numbers on a per-processor basis (or per channel basis if used
standalone). A sequence starts at ``1L`` and doesn't contain gaps unless a processor deletes messages.

.. _snapshots:

Snapshots
=========

Snapshots can dramatically reduce recovery times of processors and views. The following discusses snapshots
in context of processors but this is also applicable to views.

Processors can save snapshots of internal state by calling the  ``saveSnapshot`` method. If saving of a snapshot
succeeds, the processor receives a ``SaveSnapshotSuccess`` message, otherwise a ``SaveSnapshotFailure`` message

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

Snapshot deletion
-----------------

A processor can delete individual snapshots by calling the ``deleteSnapshot`` method with the sequence number and the
timestamp of a snapshot as argument. To bulk-delete snapshots matching ``SnapshotSelectionCriteria``, processors should
use the ``deleteSnapshots`` method.

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
is defined by implementing ``receiveRecover`` and ``receiveCommand``. This is demonstrated in the following example.

.. includecode:: ../../../akka-samples/akka-sample-persistence-scala/src/main/scala/sample/persistence/EventsourcedExample.scala#eventsourced-example

The example defines two data types, ``Cmd`` and ``Evt`` to represent commands and events, respectively. The
``state`` of the ``ExampleProcessor`` is a list of persisted event data contained in ``ExampleState``.

The processor's ``receiveRecover`` method defines how ``state`` is updated during recovery by handling ``Evt``
and ``SnapshotOffer`` messages. The processor's ``receiveCommand`` method is a command handler. In this example,
a command is handled by generating two events which are then persisted and handled. Events are persisted by calling
``persist`` with an event (or a sequence of events) as first argument and an event handler as second argument.

The ``persist`` method persists events asynchronously and the event handler is executed for successfully persisted
events. Successfully persisted events are internally sent back to the processor as individual messages that trigger
event handler executions. An event handler may close over processor state and mutate it. The sender of a persisted
event is the sender of the corresponding command. This allows event handlers to reply to the sender of a command
(not shown).

The main responsibility of an event handler is changing processor state using event data and notifying others
about successful state changes by publishing events.

When persisting events with ``persist`` it is guaranteed that the processor will not receive further commands between
the ``persist`` call and the execution(s) of the associated event handler. This also holds for multiple ``persist``
calls in context of a single command. The example also shows how to switch between command different command handlers
with ``context.become()`` and ``context.unbecome()``.

The easiest way to run this example yourself is to download `Typesafe Activator <http://typesafe.com/platform/getstarted>`_
and open the tutorial named `Akka Persistence Samples with Scala <http://typesafe.com/activator/template/akka-sample-persistence-scala>`_.
It contains instructions on how to run the ``EventsourcedExample``.

Reliable event delivery
-----------------------

Sending events from an event handler to another actor has at-most-once delivery semantics. For at-least-once delivery,
:ref:`channels` must be used. In this case, also replayed events (received by ``receiveRecover``) must be sent to a
channel, as shown in the following example:

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#reliable-event-delivery

In larger integration scenarios, channel destinations may be actors that submit received events to an external
message broker, for example. After having successfully submitted an event, they should call ``confirm()`` on the
received ``ConfirmablePersistent`` message.

Batch writes
============

To optimize throughput, a ``Processor`` internally batches received ``Persistent`` messages under high load before
writing them to the journal (as a single batch). The batch size dynamically grows from 1 under low and moderate loads
to a configurable maximum size (default is ``200``) under high load.

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#max-message-batch-size

A new batch write is triggered by a processor as soon as a batch reaches the maximum size or if the journal completed
writing the previous batch. Batch writes are never timer-based which keeps latencies at a minimum.

Applications that want to have more explicit control over batch writes and batch sizes can send processors
``PersistentBatch`` messages.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#batch-write

``Persistent`` messages contained in a ``PersistentBatch`` are always written atomically, even if the batch
size is greater than ``max-message-batch-size``. Also, a ``PersistentBatch`` is written isolated from other batches.
``Persistent`` messages contained in a ``PersistentBatch`` are received individually by a processor.

``PersistentBatch`` messages, for example, are used internally by an ``EventsourcedProcessor`` to ensure atomic
writes of events. All events that are persisted in context of a single command are written as a single batch to the
journal (even if ``persist`` is called multiple times per command). The recovery of an ``EventsourcedProcessor``
will therefore never be done partially (with only a subset of events persisted by a single command).

Confirmation and deletion operations performed by :ref:`channels` are also batched. The maximum confirmation
and deletion batch sizes are configurable with ``akka.persistence.journal.max-confirmation-batch-size`` and
``akka.persistence.journal.max-deletion-batch-size``, respectively.

Storage plugins
===============

Storage backends for journals and snapshot stores are pluggable in Akka persistence. The default journal plugin
writes messages to LevelDB (see :ref:`local-leveldb-journal`). The default snapshot store plugin writes snapshots
as individual files to the local filesystem (see :ref:`local-snapshot-store`). Applications can provide their own
plugins by implementing a plugin API and activate them by configuration. Plugin development requires the following
imports:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#plugin-imports

Journal plugin API
------------------

A journal plugin either extends ``SyncWriteJournal`` or ``AsyncWriteJournal``.  ``SyncWriteJournal`` is an
actor that should be extended when the storage backend API only supports synchronous, blocking writes. In this
case, the methods to be implemented are:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/SyncWriteJournal.scala#journal-plugin-api

``AsyncWriteJournal`` is an actor that should be extended if the storage backend API supports asynchronous,
non-blocking writes. In this case, the methods to be implemented are:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/AsyncWriteJournal.scala#journal-plugin-api

Message replays and sequence number recovery are always asynchronous, therefore, any journal plugin must implement:

.. includecode:: ../../../akka-persistence/src/main/scala/akka/persistence/journal/AsyncRecovery.scala#journal-plugin-api

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

Pre-packaged plugins
====================

.. _local-leveldb-journal:

Local LevelDB journal
---------------------

The default journal plugin is ``akka.persistence.journal.leveldb`` which writes messages to a local LevelDB
instance. The default location of the LevelDB files is a directory named ``journal`` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#journal-config

With this plugin, each actor system runs its own private LevelDB instance.


.. _shared-leveldb-journal:

Shared LevelDB journal
----------------------

A LevelDB instance can also be shared by multiple actor systems (on the same or on different nodes). This, for
example, allows processors to failover to a backup node and continue using the shared journal instance from the
backup node.

.. warning::

  A shared LevelDB instance is a single point of failure and should therefore only be used for testing
  purposes. Highly-available, replicated journal are available as `Community plugins`_.

A shared LevelDB instance is started by instantiating the ``SharedLeveldbStore`` actor.

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-creation

By default, the shared instance writes journaled messages to a local directory named ``journal`` in the current
working directory. The storage location can be changed by configuration:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-config

Actor systems that use a shared LevelDB store must activate the ``akka.persistence.journal.leveldb-shared``
plugin.

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#shared-journal-config

This plugin must be initialized by injecting the (remote) ``SharedLeveldbStore`` actor reference. Injection is
done by calling the ``SharedLeveldbJournal.setStore`` method with the actor reference as argument.

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-usage

Internal journal commands (sent by processors) are buffered until injection completes. Injection is idempotent
i.e. only the first injection is used.

.. _local-snapshot-store:

Local snapshot store
--------------------

The default snapshot store plugin is ``akka.persistence.snapshot-store.local``. It writes snapshot files to
the local filesystem. The default storage location is a directory named ``snapshots`` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-config

Custom serialization
====================

Serialization of snapshots and payloads of ``Persistent`` messages is configurable with Akka's
:ref:`serialization-scala` infrastructure. For example, if an application wants to serialize

* payloads of type ``MyPayload`` with a custom ``MyPayloadSerializer`` and
* snapshots of type ``MySnapshot`` with a custom ``MySnapshotSerializer``

it must add

.. includecode:: code/docs/persistence/PersistenceSerializerDocSpec.scala#custom-serializer-config

to the application configuration. If not specified, a default serializer is used.

Testing
=======

When running tests with LevelDB default settings in ``sbt``, make sure to set ``fork := true`` in your sbt project
otherwise, you'll see an ``UnsatisfiedLinkError``. Alternatively, you can switch to a LevelDB Java port by setting

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#native-config

or

.. includecode:: code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-native-config

in your Akka configuration. The LevelDB Java port is for testing purposes only.

Miscellaneous
=============

State machines
--------------

State machines can be persisted by mixing in the ``FSM`` trait into processors.

.. includecode:: code/docs/persistence/PersistenceDocSpec.scala#fsm-example
