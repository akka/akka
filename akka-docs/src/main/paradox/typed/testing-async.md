## Asynchronous testing

@@@ note
For the Akka Classic documentation of this feature see @ref:[Classic Testing](../testing.md).
@@@

Asynchronous testing uses a real @apidoc[akka.actor.typed.ActorSystem] that allows you to test your Actors in a more realistic environment.

The minimal setup consists of the test procedure, which provides the desired stimuli, the actor under test,
and an actor receiving replies. Bigger systems replace the actor under test with a network of actors, apply stimuli
at varying injection points and arrange results to be sent from different emission points, but the basic principle stays
the same in that a single procedure drives the test.

### Basic example

Actor under test:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #under-test }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #under-test }

Tests create an instance of @apidoc[ActorTestKit]. This provides access to:

* An ActorSystem
* Methods for spawning Actors. These are created under the special testkit user guardian
* A method to shut down the ActorSystem from the test suite

This first example is using the "raw" `ActorTestKit` but if you are using @scala[ScalaTest]@java[JUnit] you can
simplify the tests by using the @ref:[Test framework integration](#test-framework-integration). It's still good
to read this section to understand how it works.

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-header }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-header }

Your test is responsible for shutting down the @apidoc[akka.actor.typed.ActorSystem] e.g. using @scala[`BeforeAndAfterAll` when using ScalaTest]@java[`@AfterClass` when using JUnit].

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-shutdown }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-shutdown }

The following demonstrates:

* Creating an actor from the `TestKit`'s system using `spawn`
* Creating a `TestProbe`
* Verifying that the actor under test responds via the `TestProbe`

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn }

Actors can also be spawned anonymously:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-spawn-anonymous }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-spawn-anonymous }

Note that you can add `import testKit._` to get access to the `spawn` and `createTestProbe` methods at the top level
without prefixing them with `testKit`.

#### Stopping actors
The method will wait until the actor stops or throw an assertion error in case of a timeout.

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-stop-actors }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-stop-actors }

The `stop` method can only be used for actors that were spawned by the same @apidoc[ActorTestKit]. Other actors
will not be stopped by that method.

### Observing mocked behavior

When testing a component (which may be an actor or not) that interacts with other actors it can be useful to not have to
run the other actors it depends on. Instead, you might want to create mock behaviors that accept and possibly respond to
messages in the same way the other actor would do but without executing any actual logic.
In addition to this it can also be useful to observe those interactions to assert that the component under test did send
the expected messages.
This allows the same kinds of tests as classic `TestActor`/`Autopilot`.

As an example, let's assume we'd like to test the following component:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #under-test-2 }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #under-test-2 }

In our test, we create a mocked `publisher` actor. Additionally we use `Behaviors.monitor` with a `TestProbe` in order
to be able to verify the interaction of the `producer` with the `publisher`:

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/AsyncTestingExampleSpec.scala) { #test-observe-mocked-behavior }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/AsyncTestingExampleTest.java) { #test-observe-mocked-behavior }

### Test framework integration

@@@ div { .group-java }

If you are using JUnit you can use @javadoc[TestKitJunitResource](akka.actor.testkit.typed.javadsl.TestKitJunitResource) to have the async test kit automatically
shutdown when the test is complete.

Note that the dependency on JUnit is marked as optional from the test kit module, so your project must explicitly include
a dependency on JUnit to use this.

@@@

@@@ div { .group-scala }

If you are using ScalaTest you can extend @scaladoc[ScalaTestWithActorTestKit](akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit) to
have the async test kit automatically shutdown when the test is complete. This is done in `afterAll` from
the `BeforeAndAfterAll` trait. If you override that method you should call `super.afterAll` to shutdown the
test kit.

Note that the dependency on ScalaTest is marked as optional from the test kit module, so your project must explicitly include
a dependency on ScalaTest to use this.

@@@

Scala
:  @@snip [AsyncTestingExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/ScalaTestIntegrationExampleSpec.scala) { #scalatest-integration }

Java
:  @@snip [AsyncTestingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/JunitIntegrationExampleTest.java) { #junit-integration }

### Configuration

By default the `ActorTestKit` loads configuration from `application-test.conf` if that exists, otherwise
it is using default configuration from the reference.conf resources that ship with the Akka libraries. The
application.conf of your project is not used in this case.
A specific configuration can be given as parameter when creating the TestKit.

If you prefer to use `application.conf` you can pass that as the configuration parameter to the TestKit.
It's loaded with:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #default-application-conf }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #default-application-conf }

It's often convenient to define configuration for a specific test as a `String` in the test itself and
use that as the configuration parameter to the TestKit. `ConfigFactory.parseString` can be used for that:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #parse-string }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #parse-string }

Combining those approaches using `withFallback`:

Scala
:  @@snip [TestConfigExample.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/TestConfigExample.scala) { #fallback-application-conf }

Java
:  @@snip [TestConfigExample.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/TestConfigExample.java) { #fallback-application-conf }


More information can be found in the [documentation of the configuration library](https://github.com/lightbend/config#using-the-library).

@@@ note

Note that `reference.conf` files are intended for libraries to define default values and shouldn't be used
in an application. It's not supported to override a config property owned by one library in a `reference.conf`
of another library.

@@@

### Controlling the scheduler

It can be hard to reliably unit test specific scenario's when your actor relies on timing:
especially when running many tests in parallel it can be hard to get the timing just right.
Making such tests more reliable by using generous timeouts make the tests take a long time to run.

For such situations, we provide a scheduler where you can manually, explicitly advance the clock.

Scala
:   @@snip [ManualTimerExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/ManualTimerExampleSpec.scala) { #manual-scheduling-simple }

Java
:   @@snip [ManualTimerExampleTest.scala](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/ManualTimerExampleTest.java) { #manual-scheduling-simple }


### Test of logging

To verify that certain @ref:[logging](logging.md) events are emitted there is a utility called @apidoc[typed.*.LoggingTestKit] .
You define a criteria of the expected logging events and it will assert that the given number of occurrences
of matching logging events are emitted within a block of code.

@@@ note

The @apidoc[typed.*.LoggingTestKit] implementation @ref:[requires Logback dependency](logging.md#logback).

@@@

For example, a criteria that verifies that an `INFO` level event with a message containing "Received message" is logged:

Scala
:  @@snip [LoggingDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/LoggingDocExamples.scala) { #test-logging }

Java
:  @@snip [LoggingDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/LoggingDocExamples.java) { #test-logging }

More advanced criteria can be built by chaining conditions that all must be satisfied for a matching event.

Scala
:  @@snip [LoggingDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/LoggingDocExamples.scala) { #test-logging-criteria }

Java
:  @@snip [LoggingDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/LoggingDocExamples.java) { #test-logging-criteria }

See @apidoc[typed.*.LoggingTestKit] for more details.

### Silence logging output from tests

When running tests, it's typically preferred to have the output to standard out, together with the output from the
testing framework (@scala[ScalaTest]@java[JUnit]). On one hand you want the output to be clean without logging noise,
but on the other hand you want as much information as possible if there is a test failure (for example in CI builds).

The Akka TestKit provides a `LogCapturing` utility to support this with ScalaTest or JUnit. It will buffer log events instead
of emitting them to the `ConsoleAppender` immediately (or whatever Logback appender that is configured). When
there is a test failure the buffered events are flushed to the target appenders, typically a `ConsoleAppender`.

@@@ note

The `LogCapturing` utility @ref:[requires Logback dependency](logging.md#logback).

@@@

@scala[Mix `LogCapturing` trait into the ScalaTest]@java[Add a `LogCapturing` `@Rule` in the JUnit test] like this:

Scala
:  @@snip [ScalaTestIntegrationExampleSpec.scala](/akka-actor-testkit-typed/src/test/scala/docs/akka/actor/testkit/typed/scaladsl/ScalaTestIntegrationExampleSpec.scala) { #log-capturing }

Java
:  @@snip [LogCapturingExampleTest.java](/akka-actor-testkit-typed/src/test/java/jdocs/akka/actor/testkit/typed/javadsl/LogCapturingExampleTest.java) { #log-capturing }

Then you also need to configure the `CapturingAppender` and `CapturingAppenderDelegate` in
`src/test/resources/logback-test.xml`:

@@snip [logback-test.xml](/akka-actor-typed-tests/src/test/resources/logback-doc-test.xml)
