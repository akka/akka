.. _persistence-lambda-java:

######################################
Persistence (Java with Lambda Support)
######################################


Akka persistence enables stateful actors to persist their internal state so that it can be recovered when an actor
is started, restarted after a JVM crash or by a supervisor, or migrated in a cluster. The key concept behind Akka
persistence is that only changes to an actor's internal state are persisted but never its current state directly
(except for optional snapshots). These changes are only ever appended to storage, nothing is ever mutated, which
allows for very high transaction rates and efficient replication. Stateful actors are recovered by replaying stored
changes to these actors from which they can rebuild internal state. This can be either the full history of changes
or starting from a snapshot which can dramatically reduce recovery times. Akka persistence also provides point-to-point
communication with at-least-once message delivery semantics.

Akka persistence is inspired by the `eventsourced`_ library. It follows the same concepts and architecture of
`eventsourced`_ but significantly differs on API and implementation level.

.. _eventsourced: https://github.com/eligosource/eventsourced

Dependencies
============

Akka persistence is a separate jar file. Make sure that you have the following dependency in your project::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-persistence_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

The Akka persistence extension comes with few built-in persistence plugins, including
in-memory heap based journal, local file-system based snapshot-store and LevelDB based journal.

LevelDB based plugins will require the following additional dependency declaration::

  <dependency>
    <groupId>org.iq80.leveldb</groupId>
    <artifactId>leveldb</artifactId>
    <version>0.7</version>
  </dependency>
  <dependency>
    <groupId>org.fusesource.leveldbjni</groupId>
    <artifactId>leveldbjni-all</artifactId>
    <version>1.8</version>
  </dependency>

Architecture
============

* *AbstractPersistentActor*: Is a persistent, stateful actor. It is able to persist events to a journal and can react to
  them in a thread-safe manner. It can be used to implement both *command* as well as *event sourced* actors.
  When a persistent actor is started or restarted, journaled messages are replayed to that actor so that it can
  recover internal state from these messages.

* *AbstractPersistentView*: A view is a persistent, stateful actor that receives journaled messages that have been written by another
  persistent actor. A view itself does not journal new messages, instead, it updates internal state only from a persistent actor's
  replicated message stream.

* *AbstractPersistentActorAtLeastOnceDelivery*: To send messages with at-least-once delivery semantics to destinations, also in
  case of sender and receiver JVM crashes.

* *AsyncWriteJournal*: A journal stores the sequence of messages sent to a persistent actor. An application can control which messages
  are journaled and which are received by the persistent actor without being journaled. The storage backend of a journal is pluggable.
  The persistence extension comes with a "leveldb" journal plugin which writes to the local filesystem.
  Replicated journals are available as `Community plugins`_.

* *Snapshot store*: A snapshot store persists snapshots of a persistent actor's or a view's internal state. Snapshots are
  used for optimizing recovery times. The storage backend of a snapshot store is pluggable.
  The persistence extension comes with a "local" snapshot storage plugin which writes to the local filesystem.
  Replicated snapshot stores are available as `Community plugins`_.

* *Event sourcing*. Based on the building blocks described above, Akka persistence provides abstractions for the
  development of event sourced applications (see section :ref:`event-sourcing-java-lambda`)

.. _Community plugins: http://akka.io/community/

.. _event-sourcing-java-lambda:

Event sourcing
==============

The basic idea behind `Event Sourcing`_ is quite simple. A persistent actor receives a (non-persistent) command
which is first validated if it can be applied to the current state. Here validation can mean anything, from simple
inspection of a command message's fields up to a conversation with several external services, for example.
If validation succeeds, events are generated from the command, representing the effect of the command. These events
are then persisted and, after successful persistence, used to change the actor's state. When the persistent actor
needs to be recovered, only the persisted events are replayed of which we know that they can be successfully applied.
In other words, events cannot fail when being replayed to a persistent actor, in contrast to commands. Event sourced
actors may of course also process commands that do not change application state such as query commands for example.

.. _Event Sourcing: http://martinfowler.com/eaaDev/EventSourcing.html

Akka persistence supports event sourcing with the ``AbstractPersistentActor`` abstract class. An actor that extends this
class uses the ``persist`` method to persist and handle events. The behavior of an ``AbstractPersistentActor``
is defined by implementing ``receiveRecover`` and ``receiveCommand``. This is demonstrated in the following example.

.. includecode:: ../../../akka-samples/akka-sample-persistence-java-lambda/src/main/java/sample/persistence/PersistentActorExample.java#persistent-actor-example

The example defines two data types, ``Cmd`` and ``Evt`` to represent commands and events, respectively. The
``state`` of the ``ExamplePersistentActor`` is a list of persisted event data contained in ``ExampleState``.

The persistent actor's ``receiveRecover`` method defines how ``state`` is updated during recovery by handling ``Evt``
and ``SnapshotOffer`` messages. The persistent actor's ``receiveCommand`` method is a command handler. In this example,
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
calls in context of a single command. Incoming messages are :ref:`stashed <internal-stash-lambda>` until the ``persist``
is completed.

If persistence of an event fails, ``onPersistFailure`` will be invoked (logging the error by default),
and the actor will unconditionally be stopped. If persistence of an event is rejected before it is
stored, e.g. due to serialization error, ``onPersistRejected`` will be invoked (logging a warning
by default), and the actor continues with next message.

The easiest way to run this example yourself is to download `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
and open the tutorial named `Akka Persistence Samples in Java with Lambdas <http://www.lightbend.com/activator/template/akka-sample-persistence-java-lambda>`_.
It contains instructions on how to run the ``PersistentActorExample``.

.. note::

  It's also possible to switch between different command handlers during normal processing and recovery
  with ``context().become()`` and ``context().unbecome()``. To get the actor into the same state after
  recovery you need to take special care to perform the same state transitions with ``become`` and
  ``unbecome`` in the ``receiveRecover`` method as you would have done in the command handler.
  Note that when using ``become`` from ``receiveRecover`` it will still only use the ``receiveRecover``
  behavior when replaying the events. When replay is completed it will use the new behavior.

Identifiers
-----------

A persistent actor must have an identifier that doesn't change across different actor incarnations.
The identifier must be defined with the ``persistenceId`` method.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#persistence-id-override

.. _recovery-java-lambda:

Recovery
--------

By default, a persistent actor is automatically recovered on start and on restart by replaying journaled messages.
New messages sent to a persistent actor during recovery do not interfere with replayed messages. New messages will
only be received by a persistent actor after recovery completes.

.. note::
  Accessing the ``sender()`` for replayed messages will always result in a ``deadLetters`` reference,
  as the original sender is presumed to be long gone. If you indeed have to notify an actor during
  recovery in the future, store its ``ActorPath`` explicitly in your persisted events.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Applications may also customise how recovery is performed by returning a customised ``Recovery`` object
in the ``recovery`` method of a ``AbstractPersistentActor``, for example setting an upper bound to the replay
which allows the actor to be replayed to a certain point "in the past" instead to its most up to date state:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#recovery-custom

Recovery can be disabled by returning ``Recovery.none`` in the ``recovery`` method of a ``PersistentActor``:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#recovery-disabled

Recovery status
^^^^^^^^^^^^^^^

A persistent actor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#recovery-status

Sometimes there is a need for performing additional initialization when the
recovery has completed before processing any other message sent to the persistent actor.
The persistent actor will receive a special :class:`RecoveryCompleted` message right after recovery
and before any other received messages.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#recovery-completed

If there is a problem with recovering the state of the actor from the journal, ``onRecoveryFailure``
is called (logging the error by default), and the actor will be stopped.

.. _internal-stash-lambda:

Internal stash 
--------------

The persistent actor has a private :ref:`stash <stash-lambda>` for internally caching incoming messages during 
:ref:`recovery <recovery-java-lambda>` or the ``persist\persistAll`` method persisting events. You can still 
use/inherit from the ``Stash`` interface. The internal stash cooperates with the normal stash by hooking into 
``unstashAll`` method and making sure messages are unstashed properly to the internal stash to maintain ordering 
guarantees.

You should be careful to not send more messages to a persistent actor than it can keep up with, otherwise the number 
of stashed messages will grow without bounds. It can be wise to protect against ``OutOfMemoryError`` by defining a 
maximum stash capacity in the mailbox configuration::

    akka.actor.default-mailbox.stash-capacity=10000

Note that the stash capacity is per actor. If you have many persistent actors, e.g. when using cluster sharding,
you may need to define a small stash capacity to ensure that the total number of stashed messages in the system
don't consume too much memory. Additionally, The persistent actor defines three strategies to handle failure when the 
internal stash capacity is exceeded. The default overflow strategy is the ``ThrowOverflowExceptionStrategy``, which 
discards the current received message and throws a ``StashOverflowException``, causing actor restart if default 
supervision strategy is used. you can override the ``internalStashOverflowStrategy`` method to return 
``DiscardToDeadLetterStrategy`` or ``ReplyToStrategy`` for any "individual" persistent actor, or define the "default" 
for all persistent actors by providing FQCN, which must be a subclass of ``StashOverflowStrategyConfigurator``, in the 
persistence configuration::

    akka.persistence.internal-stash-overflow-strategy=
      "akka.persistence.ThrowExceptionConfigurator"
    
The ``DiscardToDeadLetterStrategy`` strategy also has a pre-packaged companion configurator 
``akka.persistence.DiscardConfigurator``.

You can also query default strategy via the Akka persistence extension singleton::

    Persistence.get(context().system()).defaultInternalStashOverflowStrategy();

.. note::
  The bounded mailbox should be avoided in the persistent actor, by which the messages come from storage backends may 
  be discarded. You can use bounded stash instead of it.


Relaxed local consistency requirements and high throughput use-cases
--------------------------------------------------------------------

If faced with relaxed local consistency requirements and high throughput demands sometimes ``PersistentActor`` and its
``persist`` may not be enough in terms of consuming incoming Commands at a high rate, because it has to wait until all
Events related to a given Command are processed in order to start processing the next Command. While this abstraction is
very useful for most cases, sometimes you may be faced with relaxed requirements about consistency – for example you may
want to process commands as fast as you can, assuming that the Event will eventually be persisted and handled properly in
the background, retroactively reacting to persistence failures if needed.

The ``persistAsync`` method provides a tool for implementing high-throughput persistent actors. It will *not*
stash incoming Commands while the Journal is still working on persisting and/or user code is executing event callbacks.

In the below example, the event callbacks may be called "at any time", even after the next Command has been processed.
The ordering between events is still guaranteed ("evt-b-1" will be sent after "evt-a-2", which will be sent after "evt-a-1" etc.).

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#persist-async

.. note::
  In order to implement the pattern known as "*command sourcing*" simply call ``persistAsync`` on all incoming messages right away
  and handle them in the callback.

.. warning::
  The callback will not be invoked if the actor is restarted (or stopped) in between the call to
  ``persistAsync`` and the journal has confirmed the write.

.. _defer-java-lambda:

Deferring actions until preceding persist handlers have executed
----------------------------------------------------------------

Sometimes when working with ``persistAsync`` you may find that it would be nice to define some actions in terms of
''happens-after the previous ``persistAsync`` handlers have been invoked''. ``PersistentActor`` provides an utility method
called ``deferAsync``, which works similarly to ``persistAsync`` yet does not persist the passed in event. It is recommended to
use it for *read* operations, and actions which do not have corresponding events in your domain model.

Using this method is very similar to the persist family of methods, yet it does **not** persist the passed in event.
It will be kept in memory and used when invoking the handler.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#defer

Notice that the ``sender()`` is **safe** to access in the handler callback, and will be pointing to the original sender
of the command for which this ``deferAsync`` handler was called.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#defer-caller

.. warning::
  The callback will not be invoked if the actor is restarted (or stopped) in between the call to
  ``deferAsync`` and the journal has processed and confirmed all preceding writes.

.. _nested-persist-calls-lambda:

Nested persist calls
--------------------
It is possible to call ``persist`` and ``persistAsync`` inside their respective callback blocks and they will properly
retain both the thread safety (including the right value of ``sender()``) as well as stashing guarantees.

In general it is encouraged to create command handlers which do not need to resort to nested event persisting,
however there are situations where it may be useful. It is important to understand the ordering of callback execution in
those situations, as well as their implication on the stashing behaviour (that ``persist()`` enforces). In the following
example two persist calls are issued, and each of them issues another persist inside its callback:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#nested-persist-persist

When sending two commands to this ``PersistentActor``, the persist handlers will be executed in the following order:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#nested-persist-persist-caller

First the "outer layer" of persist calls is issued and their callbacks are applied. After these have successfully completed,
the inner callbacks will be invoked (once the events they are persisting have been confirmed to be persisted by the journal).
Only after all these handlers have been successfully invoked will the next command be delivered to the persistent Actor.
In other words, the stashing of incoming commands that is guaranteed by initially calling ``persist()`` on the outer layer
is extended until all nested ``persist`` callbacks have been handled.

It is also possible to nest ``persistAsync`` calls, using the same pattern:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#nested-persistAsync-persistAsync

In this case no stashing is happening, yet the events are still persisted and callbacks executed in the expected order:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#nested-persistAsync-persistAsync-caller

While it is possible to nest mixed ``persist`` and ``persistAsync`` with keeping their respective semantics
it is not a recommended practice, as it may lead to overly complex nesting.

.. _failures-lambda:

Failures
--------

If persistence of an event fails, ``onPersistFailure`` will be invoked (logging the error by default),
and the actor will unconditionally be stopped.

The reason that it cannot resume when persist fails is that it is unknown if the even was actually
persisted or not, and therefore it is in an inconsistent state. Restarting on persistent failures
will most likely fail anyway, since the journal is probably unavailable. It is better to stop the
actor and after a back-off timeout start it again. The ``akka.pattern.BackoffSupervisor`` actor
is provided to support such restarts.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#backoff

If persistence of an event is rejected before it is stored, e.g. due to serialization error,
``onPersistRejected`` will be invoked (logging a warning by default), and the actor continues with
next message.

If there is a problem with recovering the state of the actor from the journal when the actor is
started, ``onRecoveryFailure`` is called (logging the error by default), and the actor will be stopped.

Atomic writes
-------------

Each event is of course stored atomically, but it is also possible to store several events atomically by
using the ``persistAll`` or ``persistAllAsync`` method. That means that all events passed to that method
are stored or none of them are stored if there is an error.

The recovery of a persistent actor will therefore never be done partially with only a subset of events persisted by
`persistAll`.

Some journals may not support atomic writes of several events and they will then reject the ``persistAll``
command, i.e. ``onPersistRejected`` is called with an exception (typically ``UnsupportedOperationException``).

Batch writes
------------

In order to optimize throughput when using ``persistAsync``, a persistent actor
internally batches events to be stored under high load before writing them to
the journal (as a single batch). The batch size is dynamically determined by
how many events are emitted during the time of a journal round-trip: after
sending a batch to the journal no further batch can be sent before confirmation
has been received that the previous batch has been written. Batch writes are never
timer-based which keeps latencies at a minimum.

Message deletion
----------------

It is possible to delete all messages (journaled by a single persistent actor) up to a specified sequence number;
Persistent actors may call the ``deleteMessages`` method to this end.

Deleting messages in event sourcing based applications is typically either not used at all or used in conjunction with
:ref:`snapshotting <snapshots>`, i.e. after a snapshot has been successfully stored, a ``deleteMessages(toSequenceNr)``
up until the sequence number of the data held by that snapshot can be issued to safely delete the previous events
while still having access to the accumulated state during replays - by loading the snapshot.

The result of the ``deleteMessages`` request is signaled to the persistent actor with a ``DeleteMessagesSuccess``
message if the delete was successful or a ``DeleteMessagesFailure`` message if it failed.

Persistence status handling
---------------------------
Persisting, deleting and replaying messages can either succeed or fail.

+---------------------------------+-----------------------------+-------------------------------+-----------------------------------+
| **Method**                      | **Success**                 | **Failure / Rejection**       | **After failure handler invoked** |
+---------------------------------+-----------------------------+-------------------------------+-----------------------------------+
| ``persist`` / ``persistAsync``  | persist handler invoked     | ``onPersistFailure``          | Actor is stopped.                 |
|                                 |                             +-------------------------------+-----------------------------------+
|                                 |                             | ``onPersistRejected``         | No automatic actions.             |
+---------------------------------+-----------------------------+-------------------------------+-----------------------------------+
| ``recovery``                    | ``RecoverySuccess``         | ``onRecoveryFailure``         | Actor is stopped.                 |
+---------------------------------+-----------------------------+-------------------------------+-----------------------------------+
| ``deleteMessages``              | ``DeleteMessagesSuccess``   | ``DeleteMessagesFailure``     | No automatic actions.             |
+---------------------------------+-----------------------------+-------------------------------+-----------------------------------+

The most important operations (``persist`` and ``recovery``) have failure handlers modelled as explicit callbacks which
the user can override in the ``PersistentActor``. The default implementations of these handlers emit a log message
(``error`` for persist/recovery failures, and ``warning`` for others), logging the failure cause and information about
which message caused the failure.

For critical failures, such as recovery or persisting events failing, the persistent actor will be stopped after the failure
handler is invoked. This is because if the underlying journal implementation is signalling persistence failures it is most
likely either failing completely or overloaded and restarting right-away and trying to persist the event again will most
likely not help the journal recover – as it would likely cause a `Thundering herd problem`_, as many persistent actors
would restart and try to persist their events again. Instead, using a ``BackoffSupervisor`` (as described in :ref:`failures-lambda`) which
implements an exponential-backoff strategy which allows for more breathing room for the journal to recover between
restarts of the persistent actor.

.. note::
  Journal implementations may choose to implement a retry mechanism, e.g. such that only after a write fails N number
  of times a persistence failure is signalled back to the user. In other words, once a journal returns a failure,
  it is considered *fatal* by Akka Persistence, and the persistent actor which caused the failure will be stopped.

  Check the documentation of the journal implementation you are using for details if/how it is using this technique.

.. _Thundering herd problem: https://en.wikipedia.org/wiki/Thundering_herd_problem

.. _safe-shutdown-lambda:

Safely shutting down persistent actors
--------------------------------------

Special care should be given when when shutting down persistent actors from the outside.
With normal Actors it is often acceptable to use the special :ref:`PoisonPill <poison-pill-java>` message
to signal to an Actor that it should stop itself once it receives this message – in fact this message is handled
automatically by Akka, leaving the target actor no way to refuse stopping itself when given a poison pill.

This can be dangerous when used with :class:`PersistentActor` due to the fact that incoming commands are *stashed* while
the persistent actor is awaiting confirmation from the Journal that events have been written when ``persist()`` was used.
Since the incoming commands will be drained from the Actor's mailbox and put into its internal stash while awaiting the
confirmation (thus, before calling the persist handlers) the Actor **may receive and (auto)handle the PoisonPill
before it processes the other messages which have been put into its stash**, causing a pre-mature shutdown of the Actor.

.. warning::
  Consider using explicit shut-down messages instead of :class:`PoisonPill` when working with persistent actors.

The example below highlights how messages arrive in the Actor's mailbox and how they interact with its internal stashing
mechanism when ``persist()`` is used. Notice the early stop behaviour that occurs when ``PoisonPill`` is used:

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#safe-shutdown
.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#safe-shutdown-example-bad
.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#safe-shutdown-example-good

.. _persistent-views-java-lambda:

Persistent Views
================

.. warning::

  ``AbstractPersistentView`` is deprecated. Use :ref:`persistence-query-java` instead. The corresponding
  query type is ``EventsByPersistenceId``. There are several alternatives for connecting the ``Source``
  to an actor corresponding to a previous ``UntypedPersistentView`` actor:

  * `Sink.actorRef`_ is simple, but has the disadvantage that there is no back-pressure signal from the
    destination actor, i.e. if the actor is not consuming the messages fast enough the mailbox of the actor will grow
  * `mapAsync`_ combined with :ref:`actors-ask-lambda` is almost as simple with the advantage of back-pressure
    being propagated all the way
  * `ActorSubscriber`_ in case you need more fine grained control

  The consuming actor may be a plain ``AbstractActor`` or an ``AbstractPersistentActor`` if it needs to store its
  own state (e.g. fromSequenceNr offset).

.. _Sink.actorRef: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/java/stream-integrations.html#Sink_actorRef
.. _mapAsync: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/stages-overview.html#Asynchronous_processing_stages
.. _ActorSubscriber: http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/java/stream-integrations.html#ActorSubscriber

Persistent views can be implemented by extending the ``AbstractView`` abstract class, implement the ``persistenceId`` method
and setting the “initial behavior” in the constructor by calling the :meth:`receive` method.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#view

The ``persistenceId`` identifies the persistent actor from which the view receives journaled messages. It is not necessary that
the referenced persistent actor is actually running. Views read messages from a persistent actor's journal directly. When a
persistent actor is started later and begins to write new messages, by default the corresponding view is updated automatically.

It is possible to determine if a message was sent from the Journal or from another actor in user-land by calling the ``isPersistent``
method. Having that said, very often you don't need this information at all and can simply apply the same logic to both cases
(skip the ``if isPersistent`` check).

Updates
-------

The default update interval of all persistent views of an actor system is configurable:

.. includecode:: ../scala/code/docs/persistence/PersistenceDocSpec.scala#auto-update-interval

``AbstractPersistentView`` implementation classes may also override the ``autoUpdateInterval`` method to return a custom update
interval for a specific view class or view instance. Applications may also trigger additional updates at
any time by sending a view an ``Update`` message.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#view-update

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

Initial recovery of persistent views works the very same way as for persistent actors (i.e. by sending a ``Recover`` message
to self). The maximum number of replayed messages during initial recovery is determined by ``autoUpdateReplayMax``.
Further possibilities to customize initial recovery are explained in section :ref:`recovery-java`.

.. _persistence-identifiers-java-lambda:

Identifiers
-----------

A persistent view must have an identifier that doesn't change across different actor incarnations.
The identifier must be defined with the ``viewId`` method.

The ``viewId`` must differ from the referenced ``persistenceId``, unless :ref:`snapshots-java-lambda` of a view and its
persistent actor should be shared (which is what applications usually do not want).

.. _snapshots-java-lambda:

Snapshots
=========

Snapshots can dramatically reduce recovery times of persistent actors and views. The following discusses snapshots
in context of persistent actors but this is also applicable to persistent views.

Persistent actor can save snapshots of internal state by calling the  ``saveSnapshot`` method. If saving of a snapshot
succeeds, the persistent actor receives a ``SaveSnapshotSuccess`` message, otherwise a ``SaveSnapshotFailure`` message

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#save-snapshot

During recovery, the persistent actor is offered a previously saved snapshot via a ``SnapshotOffer`` message from
which it can initialize internal state.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#snapshot-offer

The replayed messages that follow the ``SnapshotOffer`` message, if any, are younger than the offered snapshot.
They finally recover the persistent actor to its current (i.e. latest) state.

In general, a persistent actor is only offered a snapshot if that persistent actor has previously saved one or more snapshots
and at least one of these snapshots matches the ``SnapshotSelectionCriteria`` that can be specified for recovery.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#snapshot-criteria

If not specified, they default to ``SnapshotSelectionCriteria.latest()`` which selects the latest (= youngest) snapshot.
To disable snapshot-based recovery, applications should use ``SnapshotSelectionCriteria.none()``. A recovery where no
saved snapshot matches the specified ``SnapshotSelectionCriteria`` will replay all journaled messages.

.. note::
  In order to use snapshots, a default snapshot-store (``akka.persistence.snapshot-store.plugin``) must be configured,
  or the persistent actor can pick a snapshot store explicitly by overriding ``String snapshotPluginId()``.

  Since it is acceptable for some applications to not use any snapshotting, it is legal to not configure a snapshot store.
  However Akka will log a warning message when this situation is detected and then continue to operate until
  an actor tries to store a snapshot, at which point the operation will fail (by replying with an ``SaveSnapshotFailure`` for example).

  Note that :ref:`cluster_sharding_java` is using snapshots, so if you use Cluster Sharding you need to define a snapshot store plugin.

Snapshot deletion
-----------------

A persistent actor can delete individual snapshots by calling the ``deleteSnapshot`` method with the sequence number of
when the snapshot was taken.

To bulk-delete a range of snapshots matching ``SnapshotSelectionCriteria``,
persistent actors should use the ``deleteSnapshots`` method.

Snapshot status handling
------------------------

Saving or deleting snapshots can either succeed or fail – this information is reported back to the persistent actor via
status messages as illustrated in the following table.

============================================== ========================== ==============================
**Method**                                     **Success**                **Failure message**
============================================== ========================== ==============================
``saveSnapshot(Any)``                          ``SaveSnapshotSuccess``    ``SaveSnapshotFailure``
``deleteSnapshot(Long)``                       ``DeleteSnapshotSuccess``  ``DeleteSnapshotFailure``
``deleteSnapshots(SnapshotSelectionCriteria)`` ``DeleteSnapshotsSuccess`` ``DeleteSnapshotsFailure``
============================================== ========================== ==============================

.. _at-least-once-delivery-java-lambda:

At-Least-Once Delivery
======================

To send messages with at-least-once delivery semantics to destinations you can extend the ``AbstractPersistentActorWithAtLeastOnceDelivery``
class instead of ``AbstractPersistentActor`` on the sending side.  It takes care of re-sending messages when they
have not been confirmed within a configurable timeout.

The state of the sending actor, including which messages have been sent that have not been
confirmed by the recepient must be persistent so that it can survive a crash of the sending actor
or JVM. The ``AbstractPersistentActorWithAtLeastOnceDelivery`` class does not persist anything by itself.
It is your responsibility to persist the intent that a message is sent and that a confirmation has been
received.

.. note::

  At-least-once delivery implies that original message send order is not always preserved,
  and the destination may receive duplicate messages. Semantics do not match those of a normal :class:`ActorRef` send operation:

  * it is not at-most-once delivery

  * message order for the same sender–receiver pair is not preserved due to
    possible resends

  * after a crash and restart of the destination messages are still
    delivered to the new actor incarnation

  These semantics are similar to what an :class:`ActorPath` represents (see
  :ref:`actor-lifecycle-scala`), therefore you need to supply a path and not a
  reference when delivering messages. The messages are sent to the path with
  an actor selection.

Use the ``deliver`` method to send a message to a destination. Call the ``confirmDelivery`` method
when the destination has replied with a confirmation message.

Relationship between deliver and confirmDelivery
------------------------------------------------

To send messages to the destination path, use the ``deliver`` method after you have persisted the intent
to send the message.

The destination actor must send back a confirmation message. When the sending actor receives this
confirmation message you should persist the fact that the message was delivered successfully and then call
the ``confirmDelivery`` method.

If the persistent actor is not currently recovering, the ``deliver`` method will send the message to
the destination actor. When recovering, messages will be buffered until they have been confirmed using ``confirmDelivery``.
Once recovery has completed, if there are outstanding messages that have not been confirmed (during the message replay),
the persistent actor will resend these before sending any other messages.

Deliver requires a ``deliveryIdToMessage`` function to pass the provided ``deliveryId`` into the message so that the correlation
between ``deliver`` and ``confirmDelivery`` is possible. The ``deliveryId`` must do the round trip. Upon receipt
of the message, the destination actor will send the same``deliveryId`` wrapped in a confirmation message back to the sender.
The sender will then use it to call ``confirmDelivery`` method to complete the delivery routine.

.. includecode:: code/docs/persistence/LambdaPersistenceDocTest.java#at-least-once-example

The ``deliveryId`` generated by the persistence module is a strictly monotonically increasing sequence number
without gaps. The same sequence is used for all destinations of the actor, i.e. when sending to multiple
destinations the destinations will see gaps in the sequence. It is not possible to use custom ``deliveryId``.
However, you can send a custom correlation identifier in the message to the destination. You must then retain
a mapping between the internal ``deliveryId`` (passed into the ``deliveryIdToMessage`` function) and your custom
correlation id (passed into the message). You can do this by storing such mapping in a ``Map(correlationId -> deliveryId)``
from which you can retrieve the ``deliveryId`` to be passed into the ``confirmDelivery`` method once the receiver
of your message has replied with your custom correlation id.

The ``AbstractPersistentActorWithAtLeastOnceDelivery`` class has a state consisting of unconfirmed messages and a
sequence number. It does not store this state itself. You must persist events corresponding to the
``deliver`` and ``confirmDelivery`` invocations from your ``PersistentActor`` so that the state can
be restored by calling the same methods during the recovery phase of the ``PersistentActor``. Sometimes
these events can be derived from other business level events, and sometimes you must create separate events.
During recovery, calls to ``deliver`` will not send out messages, those will be sent later
if no matching ``confirmDelivery`` will have been performed.

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

The ``AbstractPersistentActorWithAtLeastOnceDelivery`` class holds messages in memory until their successful delivery has been confirmed.
The maximum number of unconfirmed messages that the actor is allowed to hold in memory
is defined by the ``maxUnconfirmedMessages`` method. If this limit is exceed the ``deliver`` method will
not accept more messages and it will throw ``AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException``.
The default value can be configured with the ``akka.persistence.at-least-once-delivery.max-unconfirmed-messages``
configuration key. The method can be overridden by implementation classes to return non-default values.

.. _event-adapters-lambda:

Event Adapters
==============

In long running projects using event sourcing sometimes the need arises to detach the data model from the domain model
completely.

Event Adapters help in situations where:

- **Version Migrations** – existing events stored in *Version 1* should be "upcasted" to a new *Version 2* representation,
  and the process of doing so involves actual code, not just changes on the serialization layer. For these scenarios
  the ``toJournal`` function is usually an identity function, however the ``fromJournal`` is implemented as
  ``v1.Event=>v2.Event``, performing the neccessary mapping inside the fromJournal method.
  This technique is sometimes refered to as "upcasting" in other CQRS libraries.
- **Separating Domain and Data models** – thanks to EventAdapters it is possible to completely separate the domain model
  from the model used to persist data in the Journals. For example one may want to use case classes in the
  domain model, however persist their protocol-buffer (or any other binary serialization format) counter-parts to the Journal.
  A simple ``toJournal:MyModel=>MyDataModel`` and ``fromJournal:MyDataModel=>MyModel`` adapter can be used to implement this feature.
- **Journal Specialized Data Types** – exposing data types understood by the underlying Journal, for example for data stores which
  understand JSON it is possible to write an EventAdapter ``toJournal:Any=>JSON`` such that the Journal can *directly* store the
  json instead of serializing the object to its binary representation.

Implementing an EventAdapter is rather stright forward:

.. includecode:: code/docs/persistence/PersistenceEventAdapterDocTest.java#identity-event-adapter

Then in order for it to be used on events coming to and from the journal you must bind it using the below configuration syntax:

.. includecode:: ../scala/code/docs/persistence/PersistenceEventAdapterDocSpec.scala#event-adapters-config

It is possible to bind multiple adapters to one class *for recovery*, in which case the ``fromJournal`` methods of all
bound adapters will be applied to a given matching event (in order of definition in the configuration). Since each adapter may
return from ``0`` to ``n`` adapted events (called as ``EventSeq``), each adapter can investigate the event and if it should
indeed adapt it return the adapted event(s) for it. Other adapters which do not have anything to contribute during this
adaptation simply return ``EventSeq.empty``. The adapted events are then delivered in-order to the ``PersistentActor`` during replay.

.. note::
  For more advanced schema evolution techniques refer to the :ref:`persistence-schema-evolution-scala` documentation.

.. _persistent-fsm-java-lambda:

Persistent FSM
==============
``AbstractPersistentFSM`` handles the incoming messages in an FSM like fashion.
Its internal state is persisted as a sequence of changes, later referred to as domain events.
Relationship between incoming messages, FSM's states and transitions, persistence of domain events is defined by a DSL.

.. warning::

  ``AbstractPersistentFSM`` is marked as **“experimental”** as of its introduction in Akka 2.4.0. We will continue to
  improve this API based on our users’ feedback, which implies that while we try to keep incompatible
  changes to a minimum the binary compatibility guarantee for maintenance releases does not apply to the
  contents of the `classes related to ``AbstractPersistentFSM``.

A Simple Example
----------------
To demonstrate the features of the ``AbstractPersistentFSM``, consider an actor which represents a Web store customer.
The contract of our "WebStoreCustomerFSMActor" is that it accepts the following commands:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-commands

``AddItem`` sent when the customer adds an item to a shopping cart
``Buy`` - when the customer finishes the purchase
``Leave`` - when the customer leaves the store without purchasing anything
``GetCurrentCart`` allows to query the current state of customer's shopping cart

The customer can be in one of the following states:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-states

``LookingAround`` customer is browsing the site, but hasn't added anything to the shopping cart
``Shopping`` customer has recently added items to the shopping cart
``Inactive`` customer has items in the shopping cart, but hasn't added anything recently
``Paid`` customer has purchased the items

.. note::

  ``AbstractPersistentFSM`` states must inherit from ``PersistentFSM.FSMState`` and implement the
  ``String identifier()`` method. This is required in order to simplify the serialization of FSM states.
  String identifiers should be unique!

Customer's actions are "recorded" as a sequence of "domain events" which are persisted. Those events are replayed on actor's
start in order to restore the latest customer's state:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-domain-events

Customer state data represents the items in customer's shopping cart:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-states-data

Here is how everything is wired together:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-fsm-body

.. note::

  State data can only be modified directly on initialization. Later it's modified only as a result of applying domain events.
  Override the ``applyEvent`` method to define how state data is affected by domain events, see the example below

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-apply-event

``andThen`` can be used to define actions which will be executed following event's persistence - convenient for "side effects" like sending a message or logging.
Notice that actions defined in ``andThen`` block are not executed on recovery:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-andthen-example

A snapshot of state data can be persisted by calling the ``saveStateSnapshot()`` method:

.. includecode:: ../../../akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java#customer-snapshot-example

On recovery state data is initialized according to the latest available snapshot, then the remaining domain events are replayed, triggering the
``applyEvent`` method.

Storage plugins
===============

Storage backends for journals and snapshot stores are pluggable in the Akka persistence extension.

A directory of persistence journal and snapshot store plugins is available at the Akka Community Projects page, see `Community plugins`_

Plugins can be selected either by "default", for all persistent actors and views,
or "individually", when persistent actor or view defines its own set of plugins.

When persistent actor or view does NOT override ``journalPluginId`` and ``snapshotPluginId`` methods,
persistence extension will use "default" journal and snapshot-store plugins configured in the ``reference.conf``::

    akka.persistence.journal.plugin = ""
    akka.persistence.snapshot-store.plugin = ""

However, these entries are provided as empty "", and require explicit user configuration via override in the user ``application.conf``.
For an example of journal plugin which writes messages to LevelDB see :ref:`local-leveldb-journal-java-lambda`.
For an example of snapshot store plugin which writes snapshots as individual files to the local filesystem see :ref:`local-snapshot-store-java-lambda`.

Applications can provide their own plugins by implementing a plugin API and activate them by configuration.
Plugin development requires the following imports:

.. includecode:: code/docs/persistence/LambdaPersistencePluginDocTest.java#plugin-imports

Eager initialization of persistence plugin
------------------------------------------

By default, persistence plugins are started on-demand, as they are used. In some case, however, it might be beneficial
to start a certain plugin eagerly. In order to do that, you should first add the ``akka.persistence.Persistence``
under the ``akka.extensions`` key. Then, specify the IDs of plugins you wish to start automatically under
``akka.persistence.journal.auto-start-journals`` and ``akka.persistence.snapshot-store.auto-start-snapshot-stores``.

Journal plugin API
------------------

A journal plugin extends ``AsyncWriteJournal``.

``AsyncWriteJournal`` is an actor and the methods to be implemented are:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncWritePlugin.java#async-write-plugin-api

If the storage backend API only supports synchronous, blocking writes, the methods should be implemented as:

.. includecode:: code/docs/persistence/LambdaPersistencePluginDocTest.java#sync-journal-plugin-api

A journal plugin must also implement the methods defined in ``AsyncRecovery`` for replays and sequence number recovery:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/journal/japi/AsyncRecoveryPlugin.java#async-replay-plugin-api

A journal plugin can be activated with the following minimal configuration:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#journal-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``.

The journal plugin instance is an actor so the methods corresponding to requests from persistent actors
are executed sequentially. It may delegate to asynchronous libraries, spawn futures, or delegate to other
actors to achive parallelism.

The journal plugin class must have a constructor without parameters or a constructor with one ``com.typesafe.config.Config``
parameter. The plugin section of the actor system's config will be passed in the config constructor parameter.

Don't run journal tasks/futures on the system default dispatcher, since that might starve other tasks.

Snapshot store plugin API
-------------------------

A snapshot store plugin must extend the ``SnapshotStore`` actor and implement the following methods:

.. includecode:: ../../../akka-persistence/src/main/java/akka/persistence/snapshot/japi/SnapshotStorePlugin.java#snapshot-store-plugin-api

A snapshot store plugin can be activated with the following minimal configuration:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-store-plugin-config

The specified plugin ``class`` must have a no-arg constructor. The ``plugin-dispatcher`` is the dispatcher
used for the plugin actor. If not specified, it defaults to ``akka.persistence.dispatchers.default-plugin-dispatcher``.

The snapshot store instance is an actor so the methods corresponding to requests from persistent actors
are executed sequentially. It may delegate to asynchronous libraries, spawn futures, or delegate to other
actors to achive parallelism.

The snapshot store plugin class must have a constructor without parameters or constructor with one ``com.typesafe.config.Config``
parameter. The plugin section of the actor system's config will be passed in the config constructor parameter.

Don't run snapshot store tasks/futures on the system default dispatcher, since that might starve other tasks.

Pre-packaged plugins
====================

.. _local-leveldb-journal-java-lambda:

Local LevelDB journal
---------------------

The LevelDB journal plugin config entry is ``akka.persistence.journal.leveldb``. It writes messages to a local LevelDB
instance. Enable this plugin by defining config property:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#leveldb-plugin-config

LevelDB based plugins will also require the following additional dependency declaration::

  <dependency>
    <groupId>org.iq80.leveldb</groupId>
    <artifactId>leveldb</artifactId>
    <version>0.7</version>
  </dependency>
  <dependency>
    <groupId>org.fusesource.leveldbjni</groupId>
    <artifactId>leveldbjni-all</artifactId>
    <version>1.8</version>
  </dependency>

The default location of LevelDB files is a directory named ``journal`` in the current working
directory. This location can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#journal-config

With this plugin, each actor system runs its own private LevelDB instance.

.. _shared-leveldb-journal-java-lambda:

Shared LevelDB journal
----------------------

A LevelDB instance can also be shared by multiple actor systems (on the same or on different nodes). This, for
example, allows persistent actors to failover to a backup node and continue using the shared journal instance from the
backup node.

.. warning::

  A shared LevelDB instance is a single point of failure and should therefore only be used for testing
  purposes. Highly-available, replicated journals are available as `Community plugins`_.

.. note::

  This plugin has been supplanted by :ref:`Persistence Plugin Proxy<persistence-plugin-proxy-lambda>`.

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

.. _local-snapshot-store-java-lambda:

Local snapshot store
--------------------

Local snapshot store plugin config entry is ``akka.persistence.snapshot-store.local``. It writes snapshot files to
the local filesystem. Enable this plugin by defining config property:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#leveldb-snapshot-plugin-config

The default storage location is a directory named ``snapshots`` in the current working
directory. This can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#snapshot-config

Note that it is not mandatory to specify a snapshot store plugin. If you don't use snapshots
you don't have to configure it.

.. _persistence-plugin-proxy-lambda:

Persistence Plugin Proxy
------------------------

A persistence plugin proxy allows sharing of journals and snapshot stores across multiple actor systems (on the same or
on different nodes). This, for example, allows persistent actors to failover to a backup node and continue using the
shared journal instance from the backup node. The proxy works by forwarding all the journal/snapshot store messages to a
single, shared, persistence plugin instance, and therefor supports any use case supported by the proxied plugin.

.. warning::

  A shared journal/snapshot store is a single point of failure and should therefore only be used for testing
  purposes. Highly-available, replicated persistence plugins are available as `Community plugins`_.

The journal and snapshot store proxies are controlled via the ``akka.persistence.journal.proxy`` and
``akka.persistence.snapshot-store.proxy`` configuration entries, respectively. Set the ``target-journal-plugin`` or
``target-snapshot-store-plugin`` keys to the underlying plugin you wish to use (for example:
``akka.persistence.journal.leveldb``). The ``start-target-journal`` and ``start-target-snapshot-store`` keys should be
set to ``on`` in exactly one actor system - this is the system that will instantiate the shared persistence plugin.
Next, the proxy needs to be told how to find the shared plugin. This can be done by setting the ``target-journal-address``
and ``target-snapshot-store-address`` configuration keys, or programmatically by calling the
``PersistencePluginProxy.setTargetLocation`` method.

.. note::

  Akka starts extensions lazily when they are required, and this includes the proxy. This means that in order for the
  proxy to work, the persistence plugin on the target node must be instantiated. This can be done by instantiating the
  ``PersistencePluginProxyExtension`` :ref:`extension<extending-akka-java>`, or by calling the ``PersistencePluginProxy.start`` method.

.. note::

  The proxied persistence plugin can (and should) be configured using its original configuration keys.


.. _custom-serialization-lambda:

Custom serialization
====================

Serialization of snapshots and payloads of ``Persistent`` messages is configurable with Akka's
:ref:`serialization-java` infrastructure. For example, if an application wants to serialize

* payloads of type ``MyPayload`` with a custom ``MyPayloadSerializer`` and
* snapshots of type ``MySnapshot`` with a custom ``MySnapshotSerializer``

it must add

.. includecode:: ../scala/code/docs/persistence/PersistenceSerializerDocSpec.scala#custom-serializer-config

to the application configuration. If not specified, a default serializer is used.

For more advanced schema evolution techniques refer to the :ref:`persistence-schema-evolution-scala` documentation.

Testing
=======

When running tests with LevelDB default settings in ``sbt``, make sure to set ``fork := true`` in your sbt project. Otherwise, you'll see an ``UnsatisfiedLinkError``. Alternatively, you can switch to a LevelDB Java port by setting

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#native-config

or

.. includecode:: ../scala/code/docs/persistence/PersistencePluginDocSpec.scala#shared-store-native-config

in your Akka configuration. The LevelDB Java port is for testing purposes only.

.. warning::
  It is not possible to test persistence provided classes (i.e. :ref:`PersistentActor <event-sourcing-java>`
  and :ref:`AtLeastOnceDelivery <at-least-once-delivery-java>`) using ``TestActorRef`` due to its *synchronous* nature.
  These traits need to be able to perform asynchronous tasks in the background in order to handle internal persistence
  related events.

  When testing Persistence based projects always rely on :ref:`asynchronous messaging using the TestKit <async-integration-testing-java>`.

Multiple persistence plugin configurations
==========================================

By default, a persistent actor or view will use "default" journal and snapshot store plugins
configured in the following sections of the ``reference.conf`` configuration resource:

.. includecode:: ../scala/code/docs/persistence/PersistenceMultiDocSpec.scala#default-config

Note that in this case the actor or view overrides only ``persistenceId`` method:

.. includecode:: ../java/code/docs/persistence/PersistenceMultiDocTest.java#default-plugins

When a persistent actor or view overrides ``journalPluginId`` and ``snapshotPluginId`` methods,
the actor or view will be serviced by these specific persistence plugins instead of the defaults:

.. includecode:: ../java/code/docs/persistence/PersistenceMultiDocTest.java#override-plugins

Note that ``journalPluginId`` and ``snapshotPluginId`` must refer to properly configured ``reference.conf``
plugin entries with a standard ``class`` property as well as settings which are specific for those plugins, i.e.:

.. includecode:: ../scala/code/docs/persistence/PersistenceMultiDocSpec.scala#override-config
