.. _persistence-java:

###########
Persistence
###########

This section describes an early access version of the Akka persistence module. Akka persistence is heavily inspired
by the `eventsourced`_ library. It follows the same concepts and architecture of `eventsourced`_ but significantly
differs on API and implementation level.

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
    <artifactId>akka-persistence_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Architecture
============

* *Processor*: A processor is a persistent actor. Messages sent to a processor are written to a journal before
  its ``onReceive`` method is called. When a processor is started or restarted, journaled messages are replayed
  to that processor, so that it can recover internal state from these messages.

* *Channel*: Channels are used by processors to communicate with other actors. They prevent that replayed messages
  are redundantly delivered to these actors.

Use cases
=========

* TODO: describe command sourcing
* TODO: describe event sourcing

Configuration
=============

By default, journaled messages are written to a directory named ``journal`` in the current working directory. This
can be changed by configuration where the specified path can be relative or absolute:

.. includecode:: ../scala/code/docs/persistence/PersistenceDocSpec.scala#config

.. _processors-java:

Processors
==========

A processor can be implemented by extending the abstract ``UntypedProcessor`` class and implementing the
``onReceive`` method.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#definition

Processors only write messages of type ``Persistent`` to the journal, others are received without being persisted.
When a processor's ``onReceive`` method is called with a ``Persistent`` message it can safely assume that this message
has been successfully written to the journal. A ``UntypedProcessor`` itself is an ``Actor`` and can therefore
be instantiated with ``actorOf``.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#usage

Recovery
--------

By default, a processor is automatically recovered on start and on restart by replaying persistent messages.
New messages sent to a processor during recovery do not interfere with replayed messages. New messages will
only be received by that processor after recovery completes.

Recovery customization
^^^^^^^^^^^^^^^^^^^^^^

Automated recovery on start can be disabled by overriding ``preStartProcessor`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-disabled

In this case, a processor must be recovered explicitly by sending it a ``Recover`` message.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-explicit

If not overridden, ``preStartProcessor`` sends a ``Recover`` message to ``getSelf()``. Applications may also override
``preStartProcessor`` to define further ``Recover`` parameters such as an upper sequence number bound, for example.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-start-custom

Automated recovery on restart can be disabled by overriding ``preRestartProcessor`` with an empty implementation.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recover-on-restart-disabled

This is useful in situations where processors are *resumed* by a supervisor (which keeps accumulated internal
state and makes a message replay unnecessary).

Recovery status
^^^^^^^^^^^^^^^

A processor can query its own recovery status via the methods

.. includecode:: code/docs/persistence/PersistenceDocTest.java#recovery-status

.. _failure-handling-java:

Failure handling
^^^^^^^^^^^^^^^^

A persistent message that caused an exception will be received again by a processor after restart. To prevent
a replay of that message during recovery it can be marked as deleted.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#deletion

Life cycle hooks
----------------

``UntypedProcessor`` implementation classes should override the ``preStartProcessor``, ``preRestartProcessor``,
``postRestartProcessor`` and ``postStopProcessor`` life cycle hooks and not ``preStart``, ``preRestart``,
``postRestart`` and ``postStop`` directly.

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

Later versions of the Akka persistence module will likely offer a possibility to migrate processor ids.

Channels
========

Channels are special actors that are used by processors to communicate with other actors (channel destinations).
Channels prevent redundant delivery of replayed messages to destinations during processor recovery. A replayed
message is retained by a channel if its previous delivery has been confirmed by a destination.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#channel-example

A channel is ready to use once it has been created, no recovery or further activation is needed. A ``Deliver``
request  instructs a channel to send a ``Persistent`` message to a destination where the sender of the ``Deliver``
request is forwarded to the destination. A processor may also reply to a message sender directly by using
``getSender()`` as channel destination.

.. includecode:: code/docs/persistence/PersistenceDocTest.java#channel-example-reply

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
application-defined channel id should be provided as argument to ``Channel.props(String)``

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
messages are assigned sequence numbers on a per-processor basis. A sequence starts at ``1L`` and doesn't contain
gaps unless a processor marks a message as deleted.

Upcoming features
=================

* Snapshot based recovery
* Configurable serialization
* Reliable channels
* Journal plugin API
* ...
