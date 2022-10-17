## Synchronous behavior testing

You are viewing the documentation for the new actor APIs, to view the Akka Classic documentation, see @ref:[Classic Testing](../testing.md).

The `BehaviorTestKit` provides a very nice way of unit testing a `Behavior` in a deterministic way, but it has
some limitations to be aware of.

Certain @apidoc[Behavior]s will be hard to test synchronously and the `BehaviorTestKit` doesn't support testing of
all features. In those cases the @ref:[asynchronous ActorTestKit](testing-async.md#asynchronous-testing) is recommended. Example of
limitations:

* Spawning of @scala[`Future`]@java[`CompletionStage`] or other asynchronous task and you rely on a callback to
  complete before observing the effect you want to test.
* Usage of scheduler is not supported.
* `EventSourcedBehavior` can't be fully tested, but it is possible to @ref:[test the core functionality](persistence-testing.md#unit-testing-with-the-behaviortestkit)
* Interactions with other actors must be stubbed.
* Blackbox testing style.
* Supervision is not supported.

The `BehaviorTestKit` will be improved and some of these problems will be removed but it will always have limitations.

The following demonstrates how to test:

* Spawning child actors
* Spawning child actors anonymously
* Sending a message either as a reply or to another actor
* Sending a message to a child actor

The examples below require the following imports:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #imports }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #imports }

Each of the tests are testing an actor that based on the message executes a different effect to be tested:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #under-test }

For creating a child actor a noop actor is created:


Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #child }

All of the tests make use of the @apidoc[BehaviorTestKit] to avoid the need for a real `ActorContext`. Some of the tests
make use of the @apidoc[TestInbox] which allows the creation of an @apidoc[akka.actor.typed.ActorRef] that can be used for synchronous testing, similar to the
`TestProbe` used for asynchronous testing.


### Spawning children

With a name:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child }

Anonymously:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-anonymous-child }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-anonymous-child }

### Sending messages

For testing sending a message a @apidoc[TestInbox] is created that provides an @apidoc[akka.actor.typed.ActorRef] and methods to assert against the
messages that have been sent to it.

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-message }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-message }

Another use case is sending a message to a child actor you can do this by looking up the @apidoc[TestInbox] for
a child actor from the @apidoc[BehaviorTestKit]:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message }

For anonymous children the actor names are generated in a deterministic way:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message-anonymous }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message-anonymous }

### Testing other effects

The @apidoc[BehaviorTestKit] keeps track other effects you can verify, look at the sub-classes of @apidoc[akka.actor.testkit.typed.Effect]

 * SpawnedAdapter
 * Stopped
 * Watched
 * WatchedWith
 * Unwatched
 * Scheduled
 * TimerScheduled
 * TimerCancelled

### Checking for Log Messages

The @apidoc[BehaviorTestKit] also keeps track of everything that is being logged. Here, you can see an example on how to check
if the behavior logged certain messages:

Scala
:  @@snip [SyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-check-logging }

Java
:  @@snip [SyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-check-logging }


See the other public methods and API documentation on @apidoc[BehaviorTestKit] for other types of verification.

