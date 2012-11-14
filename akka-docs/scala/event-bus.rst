.. _event-bus-scala:

#################
Event Bus (Scala)
#################


Originally conceived as a way to send messages to groups of actors, the
:class:`EventBus` has been generalized into a set of composable traits
implementing a simple interface:

- :meth:`subscribe(subscriber: Subscriber, classifier: Classifier): Boolean`
  subscribes the given subscriber to events with the given classifier

- :meth:`unsubscribe(subscriber: Subscriber, classifier: Classifier): Boolean`
  undoes a specific subscription

- :meth:`unsubscribe(subscriber: Subscriber)` undoes all subscriptions for the
  given subscriber

- :meth:`publish(event: Event)` publishes an event, which first is classified
  according to the specific bus (see `Classifiers`_) and then published to all
  subscribers for the obtained classifier

This mechanism is used in different places within Akka, e.g. the
:ref:`DeathWatch <deathwatch-scala>` and the `Event Stream`_. Implementations
can make use of the specific building blocks presented below.

An event bus must define the following three abstract types:

- :class:`Event` is the type of all events published on that bus

- :class:`Subscriber` is the type of subscribers allowed to register on that
  event bus

- :class:`Classifier` defines the classifier to be used in selecting
  subscribers for dispatching events

The traits below are still generic in these types, but they need to be defined
for any concrete implementation.

Classifiers
===========

The classifiers presented here are part of the Akka distribution, but rolling
your own in case you do not find a perfect match is not difficult, check the
implementation of the existing ones on `github`_.

.. _github: http://github.com/akka/akka/tree/v2.0.4/akka-actor/src/main/scala/akka/event/EventBus.scala

Lookup Classification
---------------------

The simplest classification is just to extract an arbitrary classifier from
each event and maintaining a set of subscribers for each possible classifier.
This can be compared to tuning in on a radio station. The trait
:class:`LookupClassification` is still generic in that it abstracts over how to
compare subscribers and how exactly to classify. The necessary methods to be
implemented are the following:

- :meth:`classify(event: Event): Classifier` is used for extracting the
  classifier from the incoming events.

- :meth:`compareSubscribers(a: Subscriber, b: Subscriber): Int` must define a
  partial order over the subscribers, expressed as expected from
  :meth:`java.lang.Comparable.compare`.

- :meth:`publish(event: Event, subscriber: Subscriber)` will be invoked for
  each event for all subscribers which registered themselves for the event’s
  classifier.

- :meth:`mapSize: Int` determines the initial size of the index data structure
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

- :obj:`subclassification: Subclassification[Classifier]` is an object
  providing :meth:`isEqual(a: Classifier, b: Classifier)` and
  :meth:`isSubclass(a: Classifier, b: Classifier)` to be consumed by the other
  methods of this classifier.

- :meth:`classify(event: Event): Classifier` is used for extracting the
  classifier from the incoming events.

- :meth:`publish(event: Event, subscriber: Subscriber)` will be invoked for
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

- :meth:`compareClassifiers(a: Classifier, b: Classifier): Int` is needed for
  determining matching classifiers and storing them in an ordered collection.

- :meth:`compareSubscribers(a: Subscriber, b: Subscriber): Int` is needed for
  storing subscribers in an ordered collection.

- :meth:`matches(classifier: Classifier, event: Event): Boolean` determines
  whether a given classifier shall match a given event; it is invoked for each
  subscription for all received events, hence the name of the classifier.

- :meth:`publish(event: Event, subscriber: Subscriber)` will be invoked for
  each event for all subscribers which registered themselves for a classifier
  matching this event.

This classifier takes always a time which is proportional to the number of
subscriptions, independent of how many actually match.

Actor Classification
--------------------

This classification has been developed specifically for implementing
:ref:`DeathWatch <deathwatch-scala>`: subscribers as well as classifiers are of
type :class:`ActorRef`. The abstract members are

- :meth:`classify(event: Event): ActorRef` is used for extracting the
  classifier from the incoming events.

- :meth:`mapSize: Int` determines the initial size of the index data structure
  used internally (i.e. the expected number of different classifiers).

This classifier is still is generic in the event type, and it is efficient for
all use cases.

.. _event-stream-scala:

Event Stream
============

The event stream is the main event bus of each actor system: it is used for
carrying :ref:`log messages <logging-scala>` and `Dead Letters`_ and may be
used by the user code for other purposes as well. It uses `Subchannel
Classification`_ which enables registering to related sets of channels (as is
used for :class:`RemoteLifeCycleMessage`). The following example demonstrates
how a simple subscription works:

.. includecode:: code/akka/docs/event/LoggingDocSpec.scala#deadletters

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

  system.eventStream.setLogLevel(Logging.DebugLevel)

This means that log events for a level which will not be logged are 
typically not dispatched at all (unless manual subscriptions to the respective
event class have been done)

Dead Letters
------------

As described at :ref:`stopping-actors-scala`, messages queued when an actor
terminates or sent after its death are re-routed to the dead letter mailbox,
which by default will publish the messages wrapped in :class:`DeadLetter`. This
wrapper holds the original sender, receiver and message of the envelope which
was redirected.

Other Uses
----------

The event stream is always there and ready to be used, just publish your own
events (it accepts ``AnyRef``) and subscribe listeners to the corresponding JVM
classes.

