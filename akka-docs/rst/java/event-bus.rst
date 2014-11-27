.. _event-bus-java:

###########
 Event Bus
###########


Originally conceived as a way to send messages to groups of actors, the
:class:`EventBus` has been generalized into a set of abstract base classes
implementing a simple interface:

.. includecode:: code/docs/event/EventBusDocTest.java#event-bus-api

.. note::

    Please note that the EventBus does not preserve the sender of the
    published messages. If you need a reference to the original sender
    you have to provide it inside the message.

This mechanism is used in different places within Akka, e.g. the `Event Stream`_.
Implementations can make use of the specific building blocks presented below.

An event bus must define the following three type parameters:

- :class:`Event` (E) is the type of all events published on that bus

- :class:`Subscriber` (S) is the type of subscribers allowed to register on that
  event bus

- :class:`Classifier` (C) defines the classifier to be used in selecting
  subscribers for dispatching events

The traits below are still generic in these types, but they need to be defined
for any concrete implementation.

Classifiers
===========

The classifiers presented here are part of the Akka distribution, but rolling
your own in case you do not find a perfect match is not difficult, check the
implementation of the existing ones on `github <@github@/akka-actor/src/main/scala/akka/event/EventBus.scala>`_ 

Lookup Classification
---------------------

The simplest classification is just to extract an arbitrary classifier from
each event and maintaining a set of subscribers for each possible classifier.
This can be compared to tuning in on a radio station. The trait
:class:`LookupClassification` is still generic in that it abstracts over how to
compare subscribers and how exactly to classify.

The necessary methods to be implemented are illustrated with the following example:

.. includecode:: code/docs/event/EventBusDocTest.java#lookup-bus

A test for this implementation may look like this:

.. includecode:: code/docs/event/EventBusDocTest.java#lookup-bus-test

This classifier is efficient in case no subscribers exist for a particular event.

Subchannel Classification
-------------------------

If classifiers form a hierarchy and it is desired that subscription be possible
not only at the leaf nodes, this classification may be just the right one. It
can be compared to tuning in on (possibly multiple) radio channels by genre.
This classification has been developed for the case where the classifier is
just the JVM class of the event and subscribers may be interested in
subscribing to all subclasses of a certain class, but it may be used with any
classifier hierarchy.

The necessary methods to be implemented are illustrated with the following example:

.. includecode:: code/docs/event/EventBusDocTest.java#subchannel-bus

A test for this implementation may look like this:

.. includecode:: code/docs/event/EventBusDocTest.java#subchannel-bus-test

This classifier is also efficient in case no subscribers are found for an
event, but it uses conventional locking to synchronize an internal classifier
cache, hence it is not well-suited to use cases in which subscriptions change
with very high frequency (keep in mind that “opening” a classifier by sending
the first message will also have to re-check all previous subscriptions).

Scanning Classification
-----------------------

The previous classifier was built for multi-classifier subscriptions which are
strictly hierarchical, this classifier is useful if there are overlapping
classifiers which cover various parts of the event space without forming a
hierarchy. It can be compared to tuning in on (possibly multiple) radio
stations by geographical reachability (for old-school radio-wave transmission).

The necessary methods to be implemented are illustrated with the following example:

.. includecode:: code/docs/event/EventBusDocTest.java#scanning-bus

A test for this implementation may look like this:

.. includecode:: code/docs/event/EventBusDocTest.java#scanning-bus-test

This classifier takes always a time which is proportional to the number of
subscriptions, independent of how many actually match.

.. _actor-classification-java:

Actor Classification
--------------------

This classification was originally developed specifically for implementing
:ref:`DeathWatch <deathwatch-java>`: subscribers as well as classifiers are of
type :class:`ActorRef`.

This classification requires an :class:`ActorSystem` in order to perform book-keeping
operations related to the subscribers being Actors, which can terminate without first
unsubscribing from the EventBus. ActorClassification maitains a system Actor which
takes care of unsubscribing terminated actors automatically.

The necessary methods to be implemented are illustrated with the following example:

.. includecode:: code/docs/event/EventBusDocTest.java#actor-bus

A test for this implementation may look like this:

.. includecode:: code/docs/event/EventBusDocTest.java#actor-bus-test

This classifier is still is generic in the event type, and it is efficient for
all use cases.

.. _event-stream-java:

Event Stream
============

The event stream is the main event bus of each actor system: it is used for
carrying :ref:`log messages <logging-java>` and `Dead Letters`_ and may be
used by the user code for other purposes as well. It uses `Subchannel
Classification`_ which enables registering to related sets of channels (as is
used for :class:`RemotingLifecycleEvent`). The following example demonstrates
how a simple subscription works. Given a simple actor:

.. includecode:: code/docs/event/LoggingDocTest.java#imports-deadletter
.. includecode:: code/docs/event/LoggingDocTest.java#deadletter-actor

it can be subscribed like this:

.. includecode:: code/docs/event/LoggingDocTest.java#deadletters

Similarily to `Actor Classification`_, :class:`EventStream` will automatically remove subscibers when they terminate.

.. note::
   The event stream is a *local facility*, meaning that it will *not* distribute events to other nodes in a clustered environment (unless you subscribe a Remote Actor to the stream explicitly).
   If you need to broadcast events in an Akka cluster, *without* knowing your recipients explicitly (i.e. obtaining their ActorRefs), you may want to look into: :ref:`distributed-pub-sub`.

Default Handlers
----------------

Upon start-up the actor system creates and subscribes actors to the event
stream for logging: these are the handlers which are configured for example in
``application.conf``:

.. code-block:: text

  akka {
    loggers = ["akka.event.Logging$DefaultLogger"]
  }

The handlers listed here by fully-qualified class name will be subscribed to
all log event classes with priority higher than or equal to the configured
log-level and their subscriptions are kept in sync when changing the log-level
at runtime::

  system.eventStream.setLogLevel(Logging.DebugLevel());

This means that log events for a level which will not be logged are
typically not dispatched at all (unless manual subscriptions to the respective
event class have been done)

Dead Letters
------------

As described at :ref:`stopping-actors-java`, messages queued when an actor
terminates or sent after its death are re-routed to the dead letter mailbox,
which by default will publish the messages wrapped in :class:`DeadLetter`. This
wrapper holds the original sender, receiver and message of the envelope which
was redirected.

Some internal messages (marked with the :class:`DeadLetterSuppression` trait) will not end up as
dead letters like normal messages. These are by design safe and expected to sometimes arrive at a terminated actor
and since they are nothing to worry about, they are suppressed from the default dead letters logging mechanism.

However, in case you find yourself in need of debugging these kinds of low level suppressed dead letters,
it's still possible to subscribe to them explicitly:

.. includecode:: code/docs/event/LoggingDocTest.java#suppressed-deadletters

or all dead letters (including the suppressed ones):

.. includecode:: code/docs/event/LoggingDocTest.java#all-deadletters

Other Uses
----------

The event stream is always there and ready to be used, just publish your own
events (it accepts ``Object``) and subscribe listeners to the corresponding JVM
classes.

