# Actor lifecycle

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

TODO intro

## Creating Actors

An actor can create, or _spawn_, an arbitrary number of child actors, which in turn can spawn children of their own, thus
forming an actor hierarchy. @unidoc[akka.actor.typed.ActorSystem] hosts the hierarchy and there can be only one _root actor_,
actor at the top of the hierarchy, per `ActorSystem`. The lifecycle of a child actor is tied to the parent -- a child
can stop itself or be stopped at any time but it can never outlive its parent.

The root actor, also called the guardian actor, is created along with the `ActorSystem`. Messages sent to the actor system are directed to the root actor.
The root actor is defined by the behavior used to create the `ActorSystem`, named `HelloWorldMain.main` in the example below:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world }


Child actors are spawned with @unidoc[akka.actor.typed.ActorContext]'s `spawn`. In the example below, when the root actor
is started, it spawns a child actor described by the behavior `HelloWorld.greeter`. Additionally, when the root actor receives a
`Start` message, it creates a child actor defined by the behavior `HelloWorldBot.bot`:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world-main }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world-main }

To specify a dispatcher when spawning an actor use @unidoc[DispatcherSelector]. If not specified, the actor will
use the default dispatcher, see @ref:[Default dispatcher](../dispatchers.md#default-dispatcher) for details.

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world-main-with-dispatchers }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world-main-with-dispatchers }

Refer to @ref:[Actors](actors.md#introduction) for a walk-through of the above examples.

## Stopping Actors

An actor can stop itself by returning `Behaviors.stopped` as the next behavior.

Child actors can be forced to be stopped after it finishes processing its current message by using the
`stop` method of the `ActorContext` from the parent actor. Only child actors can be stopped in that way.

The child actors will be stopped as part of the shutdown procedure of the parent.

The `PostStop` signal that results from stopping an actor can be used for cleaning up resources. Note that
a behavior that handles such `PostStop` signal can optionally be defined as a parameter to `Behaviors.stopped`
if different actions is needed when the actor gracefully stops itself from when it is stopped abruptly.

Here is an illustrating example:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/GracefulStopDocSpec.scala) {
    #imports
    #master-actor
    #worker-actor
    #graceful-shutdown
  }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/GracefulStopDocTest.java)  {
   #imports
   #master-actor
   #worker-actor
   #graceful-shutdown
 }
