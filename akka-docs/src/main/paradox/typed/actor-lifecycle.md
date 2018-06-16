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

Actor hierarchy consists of two kinds of actors – a root actor and its children. While there is only one root actor per
@unidoc[akka.actor.typed.ActorSystem], the root actor can create, or _spawn_, arbitrary number of child actors which can
in turn spawn child actors of their own.

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

By default, child actors will use the same dispatcher as their parent actor. Actor dispatcher can be customized
through @unidoc[akka.actor.typed.Props] or @unidoc[DispatcherSelector]:

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
