.. _event-bus-java:

################
Event Bus (Java)
################

Originally conceived as a way to send messages to groups of actors, the
:class:`EventBus` has been generalized into a set of composable traits
implementing a simple interface:

- :meth:`public boolean subscribe(S subscriber, C classifier)` subscribes the
  given subscriber to events with the given classifier

- :meth:`public boolean unsubscribe(S subscriber, C classifier)` undoes a
  specific subscription

- :meth:`public void unsubscribe(S subscriber)` undoes all subscriptions for
  the given subscriber

- :meth:`public void publish(E event)` publishes an event, which first is classified
  according to the specific bus (see `Classifiers`_) and then published to all
  subscribers for the obtained classifier

This mechanism is used in different places within Akka, e.g. the
:ref:`DeathWatch <deathwatch-java>` and the `Event Stream`_. Implementations
can make use of the specific building blocks presented below.

An event bus must define the following three abstract types:

- :class:`E` is the type of all events published on that bus

- :class:`S` is the type of subscribers allowed to register on that event bus

- :class:`C` defines the classifier to be used in selecting subscribers for
  dispatching events

The traits below are still generic in these types, but they need to be defined
for any concrete implementation.

Classifiers
===========

The classifiers presented here are part of the Akka distribution, but rolling
your own in case you do not find a perfect match is not difficult, check the
implementation of the existing ones on `github`_.

.. _github: https://github.com/akka/akka/blob/master/akka-actor/src/main/scala/akka/event/EventBus.scala

Lookup Classification
---------------------

The simplest classification is just to extract an arbitrary classifier from
each event and maintaining a set of subscribers for each possible classifier.
This can be compared to tuning in on a radio station. The abstract class
:class:`LookupEventBus` is still generic in that it abstracts over how to
compare subscribers and how exactly to classify. The necessary methods to be
implemented are the following:

- :meth:`public C classify(E event)` is used for extracting the
  classifier from the incoming events.

- :meth:`public int compareSubscribers(S a, S b)` must define a
  partial order over the subscribers, expressed as expected from
  :meth:`java.lang.Comparable.compare`.

- :meth:`public void publish(E event, S subscriber)` will be invoked for
  each event for all subscribers which registered themselves for the event’s
  classifier.

- :meth:`public int mapSize()` determines the initial size of the index data structure
  used internally (i.e. the expected number of different classifiers).

This classifier is efficient in case no subscribers exist for a particular event.

Subchannel Classification
-------------------------

If classifiers form a hierarchy and it is desired that subscription be possible
not only at the leaf nodes, this classification may be just the right one. It
can be compared to tuning in on (possibly multiple) radio channels by genre.
This classification has been developed for the case where the classifier is
just the JVM class of the event and subscribers may be interested in
subscribing to all subclasses of a certain class, but it may be used with any
classifier hierarchy. The abstract members needed by this classifier are

- :meth:`public Subclassification[C] subclassification()` provides an object
  providing :meth:`isEqual(a: Classifier, b: Classifier)` and
  :meth:`isSubclass(a: Classifier, b: Classifier)` to be consumed by the other
  methods of this classifier; this method is called on various occasions, it
  should be implemented so that it always returns the same object for
  performance reasons.

- :meth:`public C classify(E event)` is used for extracting the classifier from
  the incoming events.

- :meth:`public void publish(E event, S subscriber)` will be invoked for
  each event for all subscribers which registered themselves for the event’s
  classifier.

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
The abstract members for this classifier are:

- :meth:`public int compareClassifiers(C a, C b)` is needed for
  determining matching classifiers and storing them in an ordered collection.

- :meth:`public int compareSubscribers(S a, S b)` is needed for
  storing subscribers in an ordered collection.

- :meth:`public boolean matches(C classifier, E event)` determines
  whether a given classifier shall match a given event; it is invoked for each
  subscription for all received events, hence the name of the classifier.

- :meth:`public void publish(E event, S subscriber)` will be invoked for
  each event for all subscribers which registered themselves for a classifier
  matching this event.

This classifier takes always a time which is proportional to the number of
subscriptions, independent of how many actually match.

Actor Classification
--------------------

This classification has been developed specifically for implementing
:ref:`DeathWatch <deathwatch-java>`: subscribers as well as classifiers are of
type :class:`ActorRef`. The abstract members are

- :meth:`public ActorRef classify(E event)` is used for extracting the
  classifier from the incoming events.

- :meth:`public int mapSize()` determines the initial size of the index data structure
  used internally (i.e. the expected number of different classifiers).

This classifier is still is generic in the event type, and it is efficient for
all use cases.

.. _event-stream-java:

Event Stream
============

The event stream is the main event bus of each actor system: it is used for
carrying :ref:`log messages <logging-java>` and `Dead Letters`_ and may be
used by the user code for other purposes as well. It uses `Subchannel
Classification`_ which enables registering to related sets of channels (as is
used for :class:`RemoteLifeCycleMessage`). The following example demonstrates
how a simple subscription works. Given a simple actor:

.. includecode:: code/docs/event/LoggingDocTestBase.java#imports-deadletter
.. includecode:: code/docs/event/LoggingDocTestBase.java#deadletter-actor

it can be subscribed like this:

.. includecode:: code/docs/event/LoggingDocTestBase.java#deadletters

Default Handlers
----------------

Upon start-up the actor system creates and subscribes actors to the event
stream for logging: these are the handlers which are configured for example in
``application.conf``:

.. code-block:: text

  akka {
    event-handlers = ["akka.event.Logging$DefaultLogger"]
  }

The handlers listed here by fully-qualified class name will be subscribed to
all log event classes with priority higher than or equal to the configured
log-level and their subscriptions are kept in sync when changing the log-level
at runtime::

  system.eventStream.setLogLevel(Logging.DebugLevel());

This means that log events for a level which will not be logged are not
typically not dispatched at all (unless manual subscriptions to the respective
event class have been done)

Dead Letters
------------

As described at :ref:`stopping-actors-java`, messages queued when an actor
terminates or sent after its death are re-routed to the dead letter mailbox,
which by default will publish the messages wrapped in :class:`DeadLetter`. This
wrapper holds the original sender, receiver and message of the envelope which
was redirected.

Other Uses
----------

The event stream is always there and ready to be used, just publish your own
events (it accepts ``Object``) and subscribe listeners to the corresponding JVM
classes.

