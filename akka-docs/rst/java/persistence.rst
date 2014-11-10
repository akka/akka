.. _persistence-java:

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
communication with at-least-once message delivery semantics.

.. note::

  Java 8 lambda expressions are also supported. (See section :ref:`persistence-lambda-java`)

.. warning::

  This module is marked as **“experimental”** as of its introduction in Akka 2.3.0. We will continue to
  improve this API based on our users’ feedback, which implies that while we try to keep incompatible
  changes to a minimum the binary compatibility guarantee for maintenance releases does not apply to the
  contents of the ``akka.persistence`` package.

Akka persistence is inspired by and the official replacement of the `eventsourced`_ library. It follows the same
concepts and architecture of `eventsourced`_ but significantly differs on API and implementation level. See also
:ref:`migration-eventsourced-2.3`

.. _eventsourced: https://github.com/eligosource/eventsourced

Changes in Akka 2.3.4
=====================

In Akka 2.3.4 several of the concepts of the earlier versions were collapsed and simplified.
In essence; ``Processor`` and ``EventsourcedProcessor`` are replaced by ``PersistentActor``. ``Channel``
and ``PersistentChannel`` are replaced by ``AtLeastOnceDelivery``. ``View`` is replaced by ``PersistentView``.

See full details of the changes in the :ref:`migration-guide-persistence-experimental-2.3.x-2.4.x`.
The old classes are still included, and deprecated, for a while to make the transition smooth.
In case you need the old documentation it is located `here <http://doc.akka.io/docs/akka/2.3.3/java/persistence.html>`_.

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

* *UntypedPersistentActor*: Is a persistent, stateful actor. It is able to persist events to a journal and can react to
  them in a thread-safe manner. It can be used to implement both *command* as well as *event sourced* actors.
  When a persistent actor is started or restarted, journaled messages are replayed to that actor, so that it can
  recover internal state from these messages.

* *UntypedPersistentView*: A view is a persistent, stateful actor that receives journaled messages that have been written by another
  persistent actor. A view itself does not journal new messages, instead, it updates internal state only from a persistent actor's
  replicated message stream.

* *UntypedPersistentActorAtLeastOnceDelivery*: To send messages with at-least-once delivery semantics to destinations, also in
  case of sender and receiver JVM crashes.

* *Journal*: A journal stores the sequence of messages sent to a persistent actor. An application can control which messages
  are journaled and which are received by the persistent actor without being journaled. The storage backend of a journal is
  pluggable. The default journal storage plugin writes to the local filesystem, replicated journals are available as
  `Community plugins`_.

* *Snapshot store*: A snapshot store persists snapshots of a persistent actor's or a view's internal state. Snapshots are
  used for optimizing recovery times. The storage backend of a snapshot store is pluggable. The default snapshot
  storage plugin writes to the local filesystem.

.. _Community plugins: http://akka.io/community/

.. _event-sourcing-java:

Event sourcing
==============

The basic idea behind `Event Sourcing`_ is quite simple. A persistent actor receives a (non-persistent) command
which is first validated if it can be applied to the current state. Here, validation can mean anything, from simple
inspection of a command message's fields up to a conversation with several external services, for example.
If validation succeeds, events are generated from the command, representing the effect of the command. These events
are then persisted and, after successful persistence, used to change the actor's state. When the persistent actor
needs to be recovered, only the persisted events are replayed of which we know that they can be successfully applied.
In other words, events cannot fail when being replayed to a persistent actor, in contrast to commands. Event sourced
actors may of course also process commands that do not change application state, such as query commands, for example.

.. _Event Sourcing: http://martinfowler.com/eaaDev/EventSourcing.html

Akka persistence supports event sourcing with the ``UntypedPersistentActor`` abstract class. An actor that extends this
class uses the ``persist`` method to persist and handle events. The behavior of an ``UntypedPersistentActor``
is defined by implementing ``receiveRecover`` and ``receiveCommand``. This is demonstrated in the following example.

.. includecode:: ../../../akka-samples/akka-sample-persistence-java/src/main/java/sample/persistence/PersistentActorExample.java#persistent-actor-example

The example defines two data types, ``Cmd`` and ``Evt`` to represent commands and events, respectively. The
``state`` of the ``ExamplePersistentActor`` is a list of persisted event data contained in ``ExampleState``.

The persistent actor's ``onReceiveRecover`` method defines how ``state`` is updated during recovery by handling ``Evt``
and ``SnapshotOffer`` messages. The persistent actor's ``onReceiveCommand`` method is a command handler. In this example,
a command is handled by generating two events which are then persisted and handled. Events are persisted by calling
``persist`` with an event (or a sequence of events) as first argument and an event handler as second argument.

The ``persist`` method persists events asynchronously and the event handler is executed for successfully persisted
events. Successfully persisted events are internally sent back to the persistent actor as individual messages that trigger
event handler executions. An event handler may close over persistent actor state and mutate it. The sender of a persisted
event is the sender of the corresponding command. This allows event handlers to reply to the sender of a command
(not shown).

The main responsibility of an event handler is changing persistent actor state using event data and notifying others
about successful state changes by publishing events.

When persisting events with ``persist`` it is guaranteed that the persistent actor will not receive further commands between
the ``persist`` call and the execution(s) of the associated event handler. This also holds for multiple ``persist``
calls in context of a single command.

The easiest way to run this example yourself is to download `Typesafe Activator <http://www.typesafe.com/platform/getstarted>`_
and open the tutorial named `Akka Persistence Samples with Java <http://www.typesafe.com/activator/template/akka-sample-persistence-java>`_.
It contains instructions on how to run the ``PersistentActorExample``.

.. note::

  It's also possible to switch between different command handlers during normal processing and recovery
  with ``getContext().become()`` and ``getContext().unbecome()``. To get the actor into the same state after
  recovery you need to take special care to perform the same state transitions with ``become`` and
  ``unbecome`` in the ``receiveRecover`` method as you would have done in the command handler.

Identifiers
-----------

A persistent actor must have an identifier that doesn't change across different actor incarnations.
The identifier must be defined with the ``persistenceId`` method.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#persistence-id-override


.. _recovery-java:

Recovery
--------

By default, a persistent actor is automatically recovered on start and on restart by replaying journaled messages.
New messages sent to a persistent actor during recovery do not interfere with replayed messages. New messages will
only be received by a persistent actor after recovery completes.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Automated recovery on start can be disabled by overriding ``preStart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-disabled

In this case, a persistent actor must be recovered explicitly by sending it a ``Recover`` message.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-explicit

If not overridden, ``preStart`` sends a ``Recover`` message to ``getSelf()``. Applications may also override
``preStart`` to define further ``Recover`` parameters such as an upper sequence number bound, for example.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-custom

Upper sequence number bounds can be used to recover a persistent actor to past state instead of current state. Automated
recovery on restart can be disabled by overriding ``preRestart`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-restart-disabled

Recovery status
^^^^^^^^^^^^^^^

A persistent actor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recovery-status

Sometimes there is a need for performing additional initialization when the
recovery has completed, before processing any other message sent to the persistent actor.
The persistent actor will receive a special :class:`RecoveryCompleted` message right after recovery
and before any other received messages.

If there is a problem with recovering the state of the actor from the journal, the actor will be 
sent a :class:`RecoveryFailure` message that it can choose to handle in ``receiveRecover``. If the
actor doesn't handle the :class:`RecoveryFailure` message it will be stopped.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recovery-completed

.. _persist-async-java:

Relaxed local consistency requirements and high throughput use-cases
--------------------------------------------------------------------

If faced with relaxed local consistency requirements and high throughput demands sometimes ``PersistentActor`` and it's
``persist`` may not be enough in terms of consuming incoming Commands at a high rate, because it has to wait until all
Events related to a given Command are processed in order to start processing the next Command. While this abstraction is
very useful for most cases, sometimes you may be faced with relaxed requirements about consistency – for example you may
want to process commands as fast as you can, assuming that Event will eventually be persisted and handled properly in
the background and retroactively reacting to persistence failures if needed.

The ``persistAsync`` method provides a tool for implementing high-throughput persistent actors. It will *not*
stash incoming Commands while the Journal is still working on persisting and/or user code is executing event callbacks.

In the below example, the event callbacks may be called "at any time", even after the next Command has been processed.
The ordering between events is still guaranteed ("evt-b-1" will be sent after "evt-a-2", which will be sent after "evt-a-1" etc.).

.. includecode:: code/docs/persistence/PersistenceDocTest.java#persist-async

.. note::
  In order to implement the pattern known as "*command sourcing*" simply ``persistAsync`` all incoming events right away,
  and handle them in the callback.
  
.. warning::
  The callback will not be invoked if the actor is restarted (or stopped) in between the call to
  ``persistAsync`` and the journal has confirmed the write.  

.. _defer-java:

Deferring actions until preceding persist handlers have executed
-----------------------------------------------------------------

Sometimes when working with ``persistAsync`` you may find that it would be nice to define some actions in terms of
''happens-after the previous ``persistAsync`` handlers have been invoked''. ``PersistentActor`` provides an utility method
called ``defer``, which works similarily to ``persistAsync`` yet does not persist the passed in event. It is recommended to
use it for *read* operations, and actions which do not have corresponding events in your domain model.

Using this method is very similar to the persist family of methods, yet it does **not** persist the passed in event.
It will be kept in memory and used when invoking the handler.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#defer

Notice that the ``sender()`` is **safe** to access in the handler callback, and will be pointing to the original sender
of the command for which this ``defer`` handler was called.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#defer-caller

.. warning::
  The callback will not be invoked if the actor is restarted (or stopped) in between the call to
  ``defer`` and the journal has processed and confirmed all preceding writes.

Batch writes
------------

To optimize throughput, a persistent actor internally batches events to be stored under high load before
writing them to the journal (as a single batch). The batch size dynamically grows from 1 under low and moderate loads
to a configurable maximum size (default is ``200``) under high load. When using ``persistAsync`` this increases
the maximum throughput dramatically.

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#max-message-batch-size

A new batch write is triggered by a persistent actor as soon as a batch reaches the maximum size or if the journal completed
writing the previous batch. Batch writes are never timer-based which keeps latencies at a minimum.

The batches are also used internally to ensure atomic writes of events. All events that are persisted in context
of a single command are written as a single batch to the journal (even if ``persist`` is called multiple times per command).
The recovery of an ``UntypedPersistentActor`` will therefore never be done partially (with only a subset of events persisted by a
single command).


Message deletion
----------------

To delete all messages (journaled by a single persistent actor) up to a specified sequence number,
persistent actors may call the ``deleteMessages`` method.

An optional ``permanent`` parameter specifies whether the message shall be permanently
deleted from the journal or only marked as deleted. In both cases, the message won't be replayed. Later extensions
to Akka persistence will allow to replay messages that have been marked as deleted which can be useful for debugging
purposes, for example.

.. _persistent-views-java:

Persistent Views
================

Persistent views can be implemented by extending the ``UntypedPersistentView`` trait  and implementing the ``onReceive``
and the ``persistenceId`` methods.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#view

The ``persistenceId`` identifies the persistent actor from which the view receives journaled messages. It is not necessary
the referenced persistent actor is actually running. Views read messages from a persistent actor's journal directly. When a
persistent actor is started later and begins to write new messages, the corresponding view is updated automatically, by
default.

It is possible to determine if a message was sent from the Journal or from another actor in user-land by calling the ``isPersistent``
method. Having that said, very often you don't need this information at all and can simply apply the same logic to both cases
(skip the ``if isPersistent`` check).

Updates
-------

The default update interval of all persistent views of an actor system is configurable:

.. includecode:: ../scala/code/docs/persistence/PersistenceDocSpec.scala#auto-update-interval

``UntypedPersistentView`` implementation classes may also override the ``autoUpdateInterval`` method to return a custom update
interval for a specific view class or view instance. Applications may also trigger additional updates at
any time by sending a view an ``Update`` message.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#view-update

If the ``await`` parameter is set to ``true``, messages that follow the ``Update`` request are processed when the
incremental message replay, triggered by that update request, completed. If set to ``false`` (default), messages
following the update request may interleave with the replayed message stream. Automated updates always run with
``await = false``.

Automated updates of all persistent views of an actor system can be turned off by configuration:

.. includecode:: ../scala/code/docs/persistence/PersistenceDocSpec.scala#auto-update

Implementation classes may override the configured default value by overriding the ``autoUpdate`` method. To
limit the number of replayed messages per update request, applications can configure a custom
``akka.persistence.view.auto-update-replay-max`` value or override the ``autoUpdateReplayMax`` method. The number
of replayed messages for manual updates can be limited with the ``replayMax`` parameter of the ``Update`` message.

Recovery
--------

Initial recovery of persistent views works in the very same way as for a persistent actor (i.e. by sending a ``Recover`` message
to self). The maximum number of replayed messages during initial recovery is determined by ``autoUpdateReplayMax``.
Further possibilities to customize initial recovery are explained in section :ref:`recovery-java`.

.. _persistence-identifiers-java:

Identifiers
-----------

A persistent view must have an identifier that doesn't change across different actor incarnations.
The identifier must be defined with the ``viewId`` method.

The ``viewId`` must differ from the referenced ``persistenceId``, unless :ref:`snapshots-java` of a view and its
persistent actor shall be shared (which is what applications usually do not want).

.. _snapshots-java:

Snapshots
=========

Snapshots can dramatically reduce recovery times of persistent actor and views. The following discusses snapshots
in context of persistent actor but this is also applicable to persistent views.

Persistent actor can save snapshots of internal state by calling the  ``saveSnapshot`` method. If saving of a snapshot
succeeds, the persistent actor receives a ``SaveSnapshotSuccess`` message, otherwise a ``SaveSnapshotFailure`` message

.. includecode:: code/docs/persistence/PersistenceDocTest.java#save-snapshot

During recovery, the persistent actor is offered a previously saved snapshot via a ``SnapshotOffer`` message from
which it can initialize internal state.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#snapshot-offer

The replayed messages that follow the ``SnapshotOffer`` message, if any, are younger than the offered snapshot.
They finally recover the persistent actor to its current (i.e. latest) state.

In general, a persistent actor is only offered a snapshot if that persistent actor has previously saved one or more snapshots
and at least one of these snapshots matches the ``SnapshotSelectionCriteria`` that can be specified for recovery.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#snapshot-criteria

If not specified, they default to ``SnapshotSelectionCriteria.latest()`` which selects the latest (= youngest) snapshot.
To disable snapshot-based recovery, applications should use ``SnapshotSelectionCriteria.none()``. A recovery where no
saved snapshot matches the specified ``SnapshotSelectionCriteria`` will replay all journaled messages.

Snapshot deletion
-----------------

A persistent actor can delete individual snapshots by calling the ``deleteSnapshot`` method with the sequence number and the
timestamp of a snapshot as argument. To bulk-delete snapshots matching ``SnapshotSelectionCriteria``, persistent actors should
use the ``deleteSnapshots`` method.

.. _at-least-once-delivery-java:

At-Least-Once Delivery
======================

To send messages with at-least-once delivery semantics to destinations you can extend the ``UntypedPersistentActorWithAtLeastOnceDelivery``
class instead of ``UntypedPersistentActor`` on the sending side.  It takes care of re-sending messages when they
have not been confirmed within a configurable timeout.

.. note::

  At-least-once delivery implies that original message send order is not always preserved
  and the destination may receive duplicate messages.  That means that the
  semantics do not match those of a normal :class:`ActorRef` send operation:

  * it is not at-most-once delivery

  * message order for the same sender–receiver pair is not preserved due to
    possible resends

  * after a crash and restart of the destination messages are still
    delivered—to the new actor incarnation

  These semantics is similar to what an :class:`ActorPath` represents (see
  :ref:`actor-lifecycle-scala`), therefore you need to supply a path and not a
  reference when delivering messages. The messages are sent to the path with
  an actor selection.

Use the ``deliver`` method to send a message to a destination. Call the ``confirmDelivery`` method
when the destination has replied with a confirmation message.

Relationship between deliver and confirmDelivery
------------------------------------------------

To send messages to the destination path, use the ``deliver`` method. If the persistent actor is not currently recovering, 
this will send the message to the destination actor. When recovering, messages will be buffered until they have been confirmed using ``confirmDelivery``. 
Once recovery has completed, if there are outstanding messages that have not been confirmed (during the message replay), 
the persistent actor will resend these before sending any other messages.

Deliver also requires a function to pass the ``deliveryId`` into the message. A ``deliveryId`` is required to acknowledge 
receipt of a message, and is also used in playback, when the actor is recovering so that messages received can be correctly acknowledged. 
A function can be created to map your own ``messageId`` to ``deliveryId``, which may come from your own domain model. 
This function must keep track of which ``messageId`` have been acknowledged.
Alternatively, the Persistence module provides a default sequence number implementation which can also be used as the ``deliveryId`` 
for messages. The default sequence increases monotonically, without gaps.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#at-least-once-example

Correlation between ``deliver`` and ``confirmDelivery`` is performed with the ``deliveryId`` that is provided
as parameter to the ``deliveryIdToMessage`` function. The ``deliveryId`` is typically passed in the message to the
destination, which replies with a message containing the same ``deliveryId``.

The ``deliveryId`` is a strictly monotonically increasing sequence number without gaps. The same sequence is
used for all destinations of the actor, i.e. when sending to multiple destinations the destinations will see
gaps in the sequence if no translation is performed.

The ``UntypedPersistentActorWithAtLeastOnceDelivery`` class has a state consisting of unconfirmed messages and a
sequence number. It does not store this state itself. You must persist events corresponding to the
``deliver`` and ``confirmDelivery`` invocations from your ``PersistentActor`` so that the state can
be restored by calling the same methods during the recovery phase of the ``PersistentActor``. Sometimes
these events can be derived from other business level events, and sometimes you must create separate events.
During recovery calls to ``deliver`` will not send out the message, but it will be sent later
if no matching ``confirmDelivery`` was performed.

Support for snapshots is provided by ``getDeliverySnapshot`` and ``setDeliverySnapshot``.
The ``AtLeastOnceDeliverySnapshot`` contains the full delivery state, including unconfirmed messages.
If you need a custom snapshot for other parts of the actor state you must also include the
``AtLeastOnceDeliverySnapshot``. It is serialized using protobuf with the ordinary Akka 
serialization mechanism. It is easiest to include the bytes of the ``AtLeastOnceDeliverySnapshot``
as a blob in your custom snapshot.

The interval between redelivery attempts is defined by the ``redeliverInterval`` method.
The default value can be configured with the ``akka.persistence.at-least-once-delivery.redeliver-interval``
configuration key. The method can be overridden by implementation classes to return non-default values.

After a number of delivery attempts a ``AtLeastOnceDelivery.UnconfirmedWarning`` message
will be sent to ``self``. The re-sending will still continue, but you can choose to call
``confirmDelivery`` to cancel the re-sending. The number of delivery attempts before emitting the
warning is defined by the ``warnAfterNumberOfUnconfirmedAttempts`` method. The default value can be
configured with the ``akka.persistence.at-least-once-delivery.warn-after-number-of-unconfirmed-attempts``
configuration key. The method can be overridden by implementation classes to return non-default values.

The ``UntypedPersistentActorWithAtLeastOnceDelivery`` class holds messages in memory until their successful delivery has been confirmed.
The limit of maximum number of unconfirmed messages that the actor is allowed to hold in memory
is defined by the ``maxUnconfirmedMessages`` method. If this limit is exceed the ``deliver`` method will
not accept more messages and it will throw ``AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException``.
The default value can be configured with the ``akka.persistence.at-least-once-delivery.max-unconfirmed-messages``
configuration key. The method can be overridden by implementation classes to return non-default values.

Storage plugins
===============

Storage backends for journals and snapshot stores are pluggable in Akka persistence. The default journal plugin
writes messages to LevelDB (see :ref:`local-leveldb-journal-java`). The default snapshot store plugin writes snapshots
as individual files to the local filesystem (see :ref:`local-snapshot-store-java`). Applications can provide their own
plugins by implementing a plugin API and activate them by configuration. Plugin development requires the following
imports:

.. includecode:: code/docs/persistence/PersistencePluginDocTest.java#plugin-imports

Journal plugin API
------------------

A journal plugin either extends ``SyncWriteJournal`` or ``AsyncWriteJournal``.  ``SyncWriteJournal`` is an
actor that should be extended when the storage backend API only supports synchronous, blocking writes. In this
case, the methods to be implemented are:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/SyncWritePlugin.java#sync-write-plugin-api

``AsyncWriteJournal`` is an actor that should be extended if the storage backend API supports asynchronous,
non-blocking writes. In this case, the methods to be implemented are:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncWritePlugin.java#async-write-plugin-api

Message replays and sequence number recovery are always asynchronous, therefore, any journal plugin must implement:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncRecoveryPlugin.java#async-replay-plugin-api

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

Plugin TCK
----------
In order to help developers build correct and high quality storage plugins, we provide an Technology Compatibility Kit (`TCK <http://en.wikipedia.org/wiki/Technology_Compatibility_Kit>`_ for short).

The TCK is usable from Java as well as Scala projects, for Java you need to include the akka-persistence-tck-experimental dependency::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-persistence-tck-experimental_${scala.version}</artifactId>
    <version>2.3.5</version>
    <scope>test</scope>
  </dependency>

To include the Journal TCK tests in your test suite simply extend the provided ``JavaJournalSpec``:

.. includecode:: ./code/docs/persistence/PersistencePluginDocTest.java#journal-tck-java

We also provide a simple benchmarking class ``JavaJournalPerfSpec`` which includes all the tests that ``JavaJournalSpec``
has, and also performs some longer operations on the Journal while printing it's performance stats. While it is NOT aimed
to provide a proper benchmarking environment it can be used to get a rough feel about your journals performance in the most
typical scenarios.

In order to include the ``SnapshotStore`` TCK tests in your test suite simply extend the ``SnapshotStoreSpec``:

.. includecode:: ./code/docs/persistence/PersistencePluginDocTest.java#snapshot-store-tck-java

In case your plugin requires some setting up (starting a mock database, removing temporary files etc.) you can override the
``beforeAll`` and ``afterAll`` methods to hook into the tests lifecycle:

.. includecode:: ./code/docs/persistence/PersistencePluginDocTest.java#journal-tck-before-after-java

We *highly recommend* including these specifications in your test suite, as they cover a broad range of cases you
might have otherwise forgotten to test for when writing a plugin from scratch.

Pre-packaged plugins
====================

.. _local-leveldb-journal-java:

Local LevelDB journal
---------------------

The default journal plugin is ``akka.persistence.journal.leveldb`` which writes messages to a local LevelDB
instance. The default location of the LevelDB files is a directory named ``journal`` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#journal-config

With this plugin, each actor system runs its own private LevelDB instance.

.. _shared-leveldb-journal-java:

Shared LevelDB journal
----------------------

A LevelDB instance can also be shared by multiple actor systems (on the same or on different nodes). This, for
example, allows persistent actors to failover to a backup node and continue using the shared journal instance from the
backup node.

.. warning::

  A shared LevelDB instance is a single point of failure and should therefore only be used for testing
  purposes. Highly-available, replicated journal are available as `Community plugins`_.

A shared LevelDB instance is started by instantiating the ``SharedLeveldbStore`` actor.

.. includecode:: code/docs/persistence/PersistencePluginDocTest.java#shared-store-creation

By default, the shared instance writes journaled messages to a local directory named ``journal`` in the current
working directory. The storage location can be changed by configuration:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-config

Actor systems that use a shared LevelDB store must activate the ``akka.persistence.journal.leveldb-shared``
plugin.

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#shared-journal-config

This plugin must be initialized by injecting the (remote) ``SharedLeveldbStore`` actor reference. Injection is
done by calling the ``SharedLeveldbJournal.setStore`` method with the actor reference as argument.

.. includecode:: code/docs/persistence/PersistencePluginDocTest.java#shared-store-usage

Internal journal commands (sent by persistent actors) are buffered until injection completes. Injection is idempotent
i.e. only the first injection is used.

.. _local-snapshot-store-java:

Local snapshot store
--------------------

The default snapshot store plugin is ``akka.persistence.snapshot-store.local``. It writes snapshot files to
the local filesystem. The default storage location is a directory named ``snapshots`` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-config

Custom serialization
====================

Serialization of snapshots and payloads of ``Persistent`` messages is configurable with Akka's
:ref:`serialization-java` infrastructure. For example, if an application wants to serialize

* payloads of type ``MyPayload`` with a custom ``MyPayloadSerializer`` and
* snapshots of type ``MySnapshot`` with a custom ``MySnapshotSerializer``

it must add

.. includecode:: ../scala/code/docs/persistence/PersistenceSerializerDocSpec.scala#custom-serializer-config

to the application configuration. If not specified, a default serializer is used.

Testing
=======

When running tests with LevelDB default settings in ``sbt``, make sure to set ``fork := true`` in your sbt project
otherwise, you'll see an ``UnsatisfiedLinkError``. Alternatively, you can switch to a LevelDB Java port by setting

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#native-config

or

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-native-config

in your Akka configuration. The LevelDB Java port is for testing purposes only.

Configuration
=============

There are several configuration properties for the persistence module, please refer
to the :ref:`reference configuration <config-akka-persistence>`.

