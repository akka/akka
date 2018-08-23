# Dispatchers

## Dependency

Dispatchers are part of core akka, which means that they are part of the akka-actor-typed dependency:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor-typed_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction 

An Akka `MessageDispatcher` is what makes Akka Actors "tick", it is the engine of the machine so to speak.
All `MessageDispatcher` implementations are also an @scala[`ExecutionContext`]@java[`Executor`], which means that they can be used
to execute arbitrary code, for instance @ref:[Futures](../futures.md).

## Selecting a dispatcher

A default dispatcher is used for all actors that are spawned without specifying a custom dispatcher.
This is suitable for all actors that don't block. Blocking in actors needs to be carefully managed, more
details @ref:[here](../dispatchers.md#blocking-needs-careful-management).

To select a dispatcher use `DispatcherSelector` to create a `Props` instance for spawning your actor:

Scala
:  @@snip [DispatcherDocSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #spawn-dispatcher }

Java
:  @@snip [DispatcherDocTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/DispatchersDocTest.java) { #spawn-dispatcher }

`DispatcherSelector` has two convenience methods to look up the default dispatcher and a dispatcher you can use to 
execute actors that block e.g. a legacy database API that does not support @scala[`Future`]@java[`CompletionStage`]s.

The final example shows how to load a custom dispatcher from configuration and replies on this being in your application.conf:

Scala
:  @@snip [DispatcherDocSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #config }

Java
:  @@snip [DispatcherDocSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #config }

For full details on how to configure custom dispatchers see the @ref:[untyped docs](../dispatchers.md#types-of-dispatchers).
