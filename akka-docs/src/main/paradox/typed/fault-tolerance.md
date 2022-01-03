# Fault Tolerance

You are viewing the documentation for the new actor APIs, to view the Akka Classic documentation, see @ref:[Classic Fault Tolerance](../fault-tolerance.md).

When an actor throws an unexpected exception, a failure, while processing a message or during initialization, the actor
will by default be stopped.

@@@ note

An important difference between @ref:[Typed actors](actors.md) and @ref:[Classic actors](../actors.md) is that 
by default: the former are stopped if an exception is thrown and no supervision strategy is defined while in Classic they are restarted.

@@@

Note that there is an important distinction between failures and validation errors:

A **validation error** means that the data of a command sent to an actor is not valid, this should rather be modelled as a
part of the actor protocol than make the actor throw exceptions.

A **failure** is instead something unexpected or outside the control of the actor itself, for example a database connection
that broke. Opposite to validation errors, it is seldom useful to model failures as part of the protocol as a sending actor
can very seldomly do anything useful about it.

For failures it is useful to apply the "let it crash" philosophy: instead of mixing fine grained recovery and correction
of internal state that may have become partially invalid because of the failure with the business logic we move that
responsibility somewhere else. For many cases the resolution can then be to "crash" the actor, and start a new one,
with a fresh state that we know is valid.

## Supervision

In Akka this "somewhere else" is called supervision. Supervision allows you to declaratively describe what should happen when certain types of exceptions are thrown inside an actor. 

The default @ref:[supervision](../general/supervision.md) strategy is to stop the actor if an exception is thrown. 
In many cases you will want to further customize this behavior. To use supervision the actual Actor behavior is wrapped using @apidoc[Behaviors.supervise](typed.*.Behaviors$) {scala="#supervise[T](wrapped:akka.actor.typed.Behavior[T]):akka.actor.typed.scaladsl.Behaviors.Supervise[T]" java="#supervise(akka.actor.typed.Behavior)"}. 
Typically you would wrap the actor with supervision in the parent when spawning it as a child.
 
This example restarts the actor when it fails with an @javadoc[IllegalStateException](java.lang.IllegalStateException): 


Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #restart }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart }

Or to resume, ignore the failure and process the next message, instead:

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #resume }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #resume }

More complicated restart strategies can be used e.g. to restart no more than 10
times in a 10 second period:

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #restart-limit }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart-limit }

To handle different exceptions with different strategies calls to @apidoc[supervise](typed.*.Behaviors$) {scala="#supervise[T](wrapped:akka.actor.typed.Behavior[T]):akka.actor.typed.scaladsl.Behaviors.Supervise[T]" java="#supervise(akka.actor.typed.Behavior)"}
can be nested:

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #multiple }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #multiple }

For a full list of strategies see the public methods on @apidoc[akka.actor.typed.SupervisorStrategy].

@@@ note

When the behavior is restarted the original @apidoc[Behavior] that was given to @apidoc[Behaviors.supervise](typed.*.Behaviors$) {scala="#supervise[T](wrapped:akka.actor.typed.Behavior[T]):akka.actor.typed.scaladsl.Behaviors.Supervise[T]" java="#supervise(akka.actor.typed.Behavior)"} is re-installed,
which means that if it contains mutable state it must be a factory via @apidoc[Behaviors.setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"}. When using the
object-oriented style with a class extending @apidoc[AbstractBehavior](typed.*.AbstractBehavior) it's always recommended to create it via
@apidoc[Behaviors.setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"} as described in @ref:[Behavior factory method](style-guide.md#behavior-factory-method).
For the function style there is typically no need for the factory if the state is captured in immutable
parameters.
@@@

### Wrapping behaviors

With the @ref:[functional style](style-guide.md#functional-versus-object-oriented-style) it is very common
to store state by changing behavior e.g.

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #wrap }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #wrap }

When doing this supervision only needs to be added to the top level:

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #top-level }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #top-level }

Each returned behavior will be re-wrapped automatically with the supervisor.

## Child actors are stopped when parent is restarting

Child actors are often started in a @apidoc[setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"} block that is run again when the parent actor is restarted.
The child actors are stopped to avoid resource leaks of creating new child actors each time the parent is restarted.

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #restart-stop-children }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart-stop-children }

It is possible to override this so that child actors are not influenced when the parent actor is restarted.
The restarted parent instance will then have the same children as before the failure.

If child actors are created from @apidoc[setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"} like in the previous example and they should remain intact (not stopped)
when parent is restarted the @apidoc[supervise](typed.*.Behaviors$) {scala="#supervise[T](wrapped:akka.actor.typed.Behavior[T]):akka.actor.typed.scaladsl.Behaviors.Supervise[T]" java="#supervise(akka.actor.typed.Behavior)"} should be placed inside the @apidoc[setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"} and using
@scala[@scaladoc[SupervisorStrategy.restart.withStopChildren(false)](akka.actor.typed.RestartSupervisorStrategy#withStopChildren(enabled:Boolean):akka.actor.typed.RestartSupervisorStrategy)]@java[@javadoc[SupervisorStrategy.restart().withStopChildren(false)](akka.actor.typed.RestartSupervisorStrategy#withStopChildren(boolean))]
like this:

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #restart-keep-children }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart-keep-children }

That means that the @apidoc[setup](typed.*.Behaviors$) {scala="#setup[T](factory:akka.actor.typed.scaladsl.ActorContext[T]=%3Eakka.actor.typed.Behavior[T]):akka.actor.typed.Behavior[T]" java="#setup(akka.japi.function.Function)"} block will only be run when the parent actor is first started, and not when it is
restarted.

## The PreRestart signal

Before a supervised actor is restarted it is sent the @apidoc[PreRestart] signal giving it a chance to clean up resources
it has created, much like the @apidoc[PostStop] signal when the @ref[actor stops](actor-lifecycle.md#stopping-actors). 
The returned behavior from the @apidoc[PreRestart] signal is ignored.

Scala
:  @@snip [SupervisionCompileOnly.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/supervision/SupervisionCompileOnly.scala) { #restart-PreRestart-signal }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/supervision/SupervisionCompileOnlyTest.java) { #restart-PreRestart-signal }

Note that @apidoc[PostStop] is not emitted for a restart, so typically you need to handle both @apidoc[PreRestart] and @apidoc[PostStop]
to cleanup resources.

<a id="bubble"/>
## Bubble failures up through the hierarchy

In some scenarios it may be useful to push the decision about what to do on a failure upwards in the Actor hierarchy
 and let the parent actor handle what should happen on failures (in classic Akka Actors this is how it works by default).

For a parent to be notified when a child is terminated it has to @ref:[watch](actor-lifecycle.md#watching-actors) the
child. If the child was stopped because of a failure the @apidoc[ChildFailed] signal will be received which will contain the
cause. @apidoc[ChildFailed] extends @apidoc[Terminated](typed.Terminated) so if your use case does not need to distinguish between stopping and failing
you can handle both cases with the @apidoc[Terminated](typed.Terminated) signal.

If the parent in turn does not handle the @apidoc[Terminated](typed.Terminated) message it will itself fail with an @apidoc[DeathPactException](typed.DeathPactException).

This means that a hierarchy of actors can have a child failure bubble up making each actor on the way stop but informing the
top-most parent that there was a failure and how to deal with it, however, the original exception that caused the failure
will only be available to the immediate parent out of the box (this is most often a good thing, not leaking implementation details). 

There might be cases when you want the original exception to bubble up the hierarchy, this can be done by handling the 
@apidoc[Terminated](typed.Terminated) signal, and rethrowing the exception in each actor.

 
Scala
:  @@snip [FaultToleranceDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/FaultToleranceDocSpec.scala) { #bubbling-example }

Java
:  @@snip [SupervisionCompileOnlyTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/BubblingSample.java) { #bubbling-example }
