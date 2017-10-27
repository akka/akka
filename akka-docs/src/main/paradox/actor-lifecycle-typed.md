# Actor lifecycle

TODO intro

## Creating Actors

TODO

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
