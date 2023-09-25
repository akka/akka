# Classic Event Bus

@@include[includes.md](includes.md) { #actor-api }
For the full documentation of this feature and for new projects see @ref:[Event Bus](typed/event-stream.md).

## Dependency

@@include[typed/event-bus.md](typed/event-stream.md) { #dependency }

## Introduction

@@include[typed/event-bus.md](typed/event-stream.md) { #introduction-start }

Scala
:  @@snip [EventBus.scala](/akka-actor/src/main/scala/akka/event/EventBus.scala) { #event-bus-api }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #event-bus-api }

@@include[typed/event-bus.md](typed/event-stream.md) { #introduction-end }


## Classifiers

@@include[typed/event-bus.md](typed/event-stream.md) { #classifiers-intro }

### Lookup Classification

@@include[typed/event-bus.md](typed/event-stream.md) { #lookup-classification-start }

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #lookup-bus }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #lookup-bus }

A test for this implementation may look like this:

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #lookup-bus-test }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #lookup-bus-test }

@@include[typed/event-bus.md](typed/event-stream.md) { #lookup-classification-end }

### Subchannel Classification

@@include[typed/event-bus.md](typed/event-stream.md) { #subchannel-classification-start }

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #subchannel-bus }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #subchannel-bus }

A test for this implementation may look like this:

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #subchannel-bus-test }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #subchannel-bus-test }

@@include[typed/event-bus.md](typed/event-stream.md) { #subchannel-classification-end }

### Scanning Classification

@@include[typed/event-bus.md](typed/event-stream.md) { #scanning-classification-start }

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #scanning-bus }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #scanning-bus }

A test for this implementation may look like this:

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #scanning-bus-test }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #scanning-bus-test }

@@include[typed/event-bus.md](typed/event-stream.md) { #scanning-classification-end }


### Actor Classification

This classification was originally developed specifically for implementing
@ref:[DeathWatch](actors.md#lifecycle-monitoring-aka-deathwatch): subscribers as well as classifiers are of
type @apidoc[actor.ActorRef].

@@include[typed/event-bus.md](typed/event-stream.md) { #actor-classification-start }

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #actor-bus }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #actor-bus }

A test for this implementation may look like this:

Scala
:  @@snip [EventBusDocSpec.scala](/akka-docs/src/test/scala/docs/event/EventBusDocSpec.scala) { #actor-bus-test }

Java
:  @@snip [EventBusDocTest.java](/akka-docs/src/test/java/jdocs/event/EventBusDocTest.java) { #actor-bus-test }

@@include[typed/event-bus.md](typed/event-stream.md) { #actor-classification-end }


## Event Stream

The event stream is the main event bus of each actor system: it is used for
carrying @ref:[log messages](logging.md) and @ref:[Dead Letters](#dead-letters) and may be
used by the user code for other purposes as well. It uses @ref:[Subchannel
Classification](#subchannel-classification) which enables registering to related sets of channels.
The following example demonstrates how a simple subscription works. Given a simple actor:

@@@ div { .group-scala }

@@snip [LoggingDocSpec.scala](/akka-docs/src/test/scala/docs/event/LoggingDocSpec.scala) { #deadletters }

@@@

@@@ div { .group-java }

@@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #imports-deadletter }

@@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #deadletter-actor }

it can be subscribed like this:

@@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #deadletters }

@@@


It is also worth pointing out that thanks to the way the subchannel classification
is implemented in the event stream, it is possible to subscribe to a group of events, by
subscribing to their common superclass as demonstrated in the following example:

Scala
:  @@snip [LoggingDocSpec.scala](/akka-docs/src/test/scala/docs/event/LoggingDocSpec.scala) { #superclass-subscription-eventstream }

Java
:  @@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #superclass-subscription-eventstream }

Similarly to @ref:[Actor Classification](#actor-classification), @apidoc[event.EventStream] will automatically remove subscribers when they terminate.

@@@ note

The event stream is a *local facility*, meaning that it will *not* distribute events to other nodes in a clustered environment (unless you subscribe a Remote Actor to the stream explicitly).
If you need to broadcast events in an Akka cluster, *without* knowing your recipients explicitly (i.e. obtaining their ActorRefs), you may want to look into: @ref:[Distributed Publish Subscribe in Cluster](distributed-pub-sub.md).

@@@

### Default Handlers

Upon start-up the actor system creates and subscribes actors to the event
stream for logging: these are the handlers which are configured for example in
`application.conf`:

```text
akka {
  loggers = ["akka.event.Logging$DefaultLogger"]
}
```

The handlers listed here by fully-qualified class name will be subscribed to
all log event classes with priority higher than or equal to the configured
log-level and their subscriptions are kept in sync when changing the log-level
at runtime:

Scala
:   @@@vars
    ```
    system.eventStream.setLogLevel(Logging.DebugLevel)
    ```
    @@@

Java
:   @@@vars
    ```
    system.eventStream.setLogLevel(Logging.DebugLevel());
    ```
    @@@
    
This means that log events for a level which will not be logged are
typically not dispatched at all (unless manual subscriptions to the respective
event class have been done)

### Dead Letters

As described at @ref:[Stopping actors](actors.md#stopping-actors), messages queued when an actor
terminates or sent after its death are re-routed to the dead letter mailbox,
which by default will publish the messages wrapped in @apidoc[DeadLetter]. This
wrapper holds the original sender, receiver and message of the envelope which
was redirected.

Some internal messages (marked with the @apidoc[DeadLetterSuppression] @scala[trait]@java[interface]) will not end up as
dead letters like normal messages. These are by design safe and expected to sometimes arrive at a terminated actor
and since they are nothing to worry about, they are suppressed from the default dead letters logging mechanism.

However, in case you find yourself in need of debugging these kinds of low level suppressed dead letters,
it's still possible to subscribe to them explicitly:

Scala
:  @@snip [LoggingDocSpec.scala](/akka-docs/src/test/scala/docs/event/LoggingDocSpec.scala) { #suppressed-deadletters }

Java
:  @@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #suppressed-deadletters }

or all dead letters (including the suppressed ones):

Scala
:  @@snip [LoggingDocSpec.scala](/akka-docs/src/test/scala/docs/event/LoggingDocSpec.scala) { #all-deadletters }

Java
:  @@snip [LoggingDocTest.java](/akka-docs/src/test/java/jdocs/event/LoggingDocTest.java) { #all-deadletters }

### Other Uses

The event stream is always there and ready to be used, you can publish your own
events (it accepts @scala[@scaladoc[AnyRef](scala.AnyRef)]@java[@javadoc[Object](java.lang.Object)]) and subscribe listeners to the corresponding JVM
classes.
