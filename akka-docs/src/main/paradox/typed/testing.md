# Testing

Testing can either be done asynchronously using a real `ActorSystem` or synchronously on the testing thread using the `BehaviousTestKit`.

For testing logic in a `Behavior` in isolation synchronous testing is preferred. For testing interactions between multiple
actors a more realistic asynchronous test is preferred.

Certain `Behavior`s will be hard to test synchronously e.g. if they spawn Future's and you rely on a callback to complete
before observing the effect you want to test. Further support for controlling the scheduler and execution context used
will be added.

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Dependency

To use Akka TestKit Typed, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-testkit-typed_2.12
  version=$akka.version$
  scope=test
}

## Synchronous behavior testing

The following demonstrates how to test:

* Spawning child actors
* Spawning child actors anonymously
* Sending a message either as a reply or to another actor
* Sending a message to a child actor

The examples below require the following imports:

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #imports }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #imports }

Each of the tests are testing an actor that based on the message executes a different effect to be tested:

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #under-test }

For creating a child actor a noop actor is created:


Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #child }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #child }

All of the tests make use of the `BehaviorTestkit` to avoid the need for a real `ActorContext`. Some of the tests
make use of the `TestInbox` which allows the creation of an `ActorRef` that can be used for synchronous testing, similar to the
`TestProbe` used for asynchronous testing.


### Spawning children

With a name:

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child }

Anonymously:

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-anonymous-child }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-anonymous-child }

### Sending messages

For testing sending a message a `TestInbox` is created that provides an `ActorRef` and methods to assert against the
messages that have been sent to it.

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-message }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-message }

Another use case is sending a message to a child actor you can do this by looking up the 'TestInbox' for
a child actor from the 'BehaviorTestKit':

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message }

For anonymous children the actor names are generated in a deterministic way:

Scala
:  @@snip [SyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/SyncTestingExampleSpec.scala) { #test-child-message-anonymous }

Java
:  @@snip [SyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/SyncTestingExampleTest.java) { #test-child-message-anonymous }

### Testing other effects

The `BehaviorTestkit` keeps track other effects you can verify, look at the sub-classes of `akka.testkit.typed.Effect`

 * SpawnedAdapter
 * Stopped
 * Watched
 * Unwatched
 * Scheduled

See the other public methods and API documentation on `BehaviorTestkit` for other types of verification.

## Asynchronous testing

Asynchronous testing uses a real `ActorSystem` that allows you to test your Actors in a more realistic environment.

The minimal setup consists of the test procedure, which provides the desired stimuli, the actor under test,
and an actor receiving replies. Bigger systems replace the actor under test with a network of actors, apply stimuli
at varying injection points and arrange results to be sent from different emission points, but the basic principle stays
the same in that a single procedure drives the test.

### Basic example

Actor under test:

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #under-test }

@scala[Tests extend `ActorTestKit`. This provides access to]@java[Tests create an instance of `ActorTestKit`. This provides access to]

* An ActorSystem
* Methods for spawning Actors. These are created under the root guardian
* A hook to shut down the ActorSystem from the test suite

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-header }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-header }

Your test is responsible for shutting down the `ActorSystem` e.g. using `BeforeAndAfterAll` when using ScalaTest

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-shutdown }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-shutdown }

The following demonstrates:

* Creating a typed actor from the `TestKit`'s system using `spawn`
* Creating a typed `TestProbe`
* Verifying that the actor under test responds via the `TestProbe`

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn }

Actors can also be spawned anonymously:

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn-anonymous }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn-anonymous }

### Test framework integration

@@@ div { .group-java }

If you are using JUnit you can use `akka.testkit.typed.javadsl.TestKitJunitResource` to have the async test kit automatically
shutdown when the test is complete.

Note that the dependency on JUnit is marked as optional from the test kit module, so your project must explicitly include
a dependency on JUnit to use this.

@@@

@@@ div { .group-scala } 

It often makes sense to introduce a common base class for all tests using the async test kit, here is an example how to
hook it into a ScalaTest test suite.

@@@

Scala
:  @@snip [AsyncTestingExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/AbstractActorSpec.scala) { #scalatest-glue }

Java
:  @@snip [AsyncTestingExampleTest.java]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/JunitIntegrationExampleTest.java) { #junit-integration }

### Controlling the scheduler

It can be hard to reliably unit test specific scenario's when your actor relies on timing:
especially when running many tests in parallel it can be hard to get the timing just right.
Making such tests more reliable by using generous timeouts make the tests take a long time to run.

For such situations, we provide a scheduler where you can manually, explicitly advance the clock.

Scala
:   @@snip [ManualTimerExampleSpec.scala]($akka$/akka-testkit-typed/src/test/scala/akka/testkit/typed/scaladsl/ManualTimerExampleSpec.scala) { #manual-scheduling-simple }

Java
:   @@snip [ManualTimerExampleTest.scala]($akka$/akka-testkit-typed/src/test/java/akka/testkit/typed/javadsl/ManualTimerExampleTest.java) { #manual-scheduling-simple }
