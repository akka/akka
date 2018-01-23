# Testing 

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.
  
  
@@@

To use the testkit add the following dependency:

@@dependency [sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-testkit-typed_2.12
  version=$akka.version$
  scope=test
}
   
Testing can either be done asynchronously using a real `ActorSystem` or synchronously on the testing thread using the `BehaviousTestKit`.  

For testing logic in a `Behavior` in isolation synchronous testing is preferred. For testing interactions between multiple
actors a more realistic asynchronous test is preferred. 

Certain `Behavior`s will be hard to test synchronously e.g. if they spawn Future's and you rely on a callback to complete
before observing the effect you want to test. Further support for controlling the scheduler and execution context used
will be added.
    
## Synchronous behaviour testing

The following demonstrates how to test:

* Spawning child actors
* Spawning child actors anonymously
* Sending a message either as a reply or to another actor
* Sending a message to a child actor

The examples below require the following imports:

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #imports }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #imports }

Each of the tests are testing an actor that based on the message executes a different effect to be tested:

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #under-test }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #under-test }

For creating a child actor a noop actor is created:


Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #child }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #child }

All of the tests make use of the `BehaviorTestkit` to avoid the need for a real `ActorContext`. Some of the tests
make use of the `TestInbox` which allows the creation of an `ActorRef` that can be used for synchronous testing, similar to the
`TestProbe` used for asynchronous testing.


### Spawning children

With a name: 

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #test-child }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #test-child } 

Anonymously:

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #test-anonymous-child }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #test-anonymous-child } 

### Sending messages

For testing sending a message a `TestInbox` is created that provides an `ActorRef` and methods to assert against the
messages that have been sent to it.

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #test-message }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #test-message } 

Another use case is sending a message to a child actor you can do this by looking up the 'TestInbox' for
a child actor from the 'BehaviorTestKit':

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #test-child-message }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #test-child-message } 

For anonymous children the actor names are generated in a deterministic way:

Scala
:  @@snip [BasicSyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/sync/BasicSyncTestingSpec.scala) { #test-child-message-anonymous }

Java
:  @@snip [BasicSyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/sync/BasicSyncTestingTest.java) { #test-child-message-anonymous } 

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
:  @@snip [BasicAsyncTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/async/BasicAsyncTestingSpec.scala) { #under-test }

Java
:  @@snip [BasicAsyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/async/BasicAsyncTestingTest.java) { #under-test } 

Tests extend `TestKit` or include the `TestKitBase`. This provides access to
* An ActorSystem 
* Methods for spawning Actors. These are created under the root guardian
* Methods for creating system actors

Scala
:  @@snip [BasicTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/async/BasicAsyncTestingSpec.scala) { #test-header }

Java
:  @@snip [BasicAsyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/async/BasicAsyncTestingTest.java) { #test-header } 

Your test is responsible for shutting down the `ActorSystem` e.g. using `BeforeAndAfterAll` when using ScalaTest

Scala
:  @@snip [BasicTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/async/BasicAsyncTestingSpec.scala) { #test-shutdown }

Java
:  @@snip [BasicAsyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/async/BasicAsyncTestingTest.java) { #test-shutdown } 

The following demonstrates:

* Creating a typed actor from the `TestKit`'s system using `spawn`
* Creating a typed `TestProbe` 
* Verifying that the actor under test responds via the `TestProbe`

Scala
:  @@snip [BasicTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/async/BasicAsyncTestingSpec.scala) { #test-spawn }

Java
:  @@snip [BasicAsyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/async/BasicAsyncTestingTest.java) { #test-spawn } 

Actors can also be spawned anonymously:

Scala
:  @@snip [BasicTestingSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/testing/async/BasicAsyncTestingSpec.scala) { #test-spawn-anonymous }

Java
:  @@snip [BasicAsyncTestingTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/testing/async/BasicAsyncTestingTest.java) { #test-spawn-anonymous } 

