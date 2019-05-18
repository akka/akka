# Coexistence

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

We believe Akka Typed will be adopted in existing systems gradually and therefore it's important to be able to use typed
and untyped actors together, within the same `ActorSystem`. Also, we will not be able to integrate with all existing modules in one big bang release and that is another reason for why these two ways of writing actors must be able to coexist.

There are two different `ActorSystem`s: `akka.actor.ActorSystem` and `akka.actor.typed.ActorSystem`. 

Currently the typed actor system is implemented using an untyped actor system under the hood. This may change in the future.

Typed and untyped can interact the following ways:

* untyped actor systems can create typed actors
* typed actors can send messages to untyped actors, and opposite
* spawn and supervise typed child from untyped parent, and opposite
* watch typed from untyped, and opposite
* untyped actor system can be converted to a typed actor system

@@@ div { .group-scala }
In the examples the `akka.actor` package is aliased to `untyped`.

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #import-alias }

@@@

@java[The examples use fully qualified class names for the untyped classes to distinguish between typed and untyped classes with the same name.]

## Untyped to typed 

While coexisting your application will likely still have an untyped ActorSystem. This can be converted to a typed ActorSystem
so that new code and migrated parts don't rely on the untyped system:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #adapter-import #convert-untyped }

Java
:  @@snip [UntypedWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/UntypedWatchingTypedTest.java) { #adapter-import #convert-untyped }

Then for new typed actors here's how you create, watch and send messages to
it from an untyped actor.

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #typed }

Java
:  @@snip [UntypedWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/UntypedWatchingTypedTest.java) { #typed }

The top level untyped actor is created in the usual way:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #create-untyped }

Java
:  @@snip [UntypedWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/UntypedWatchingTypedTest.java) { #create-untyped }

Then it can create a typed actor, watch it, and send a message to it:

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #untyped-watch }

Java
:  @@snip [UntypedWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/UntypedWatchingTypedTest.java) { #untyped-watch }

@scala[There is one `import` that is needed to make that work.] @java[We import the Adapter class and
call static methods for conversion.]

Scala
:  @@snip [UntypedWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/UntypedWatchingTypedSpec.scala) { #adapter-import }

Java
:  @@snip [UntypedWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/UntypedWatchingTypedTest.java) { #adapter-import }


@scala[That adds some implicit extension methods that are added to untyped and typed `ActorSystem` and `ActorContext` in both directions.]
@java[To convert between typed and untyped there are adapter methods in `akka.actor.typed.javadsl.Adapter`.] Note the inline comments in the example above.

## Typed to untyped

Let's turn the example upside down and first start the typed actor and then the untyped as a child.

The following will show how to create, watch and send messages back and forth from a typed actor to this
untyped actor:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #untyped }

Java
:  @@snip [TypedWatchingUntypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingUntypedTest.java) { #untyped }

Creating the actor system and the typed actor:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #create }

Java
:  @@snip [TypedWatchingUntypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingUntypedTest.java) { #create }

Then the typed actor creates the untyped actor, watches it and sends and receives a response:

Scala
:  @@snip [TypedWatchingUntypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingUntypedSpec.scala) { #typed }

Java
:  @@snip [TypedWatchingUntypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingUntypedTest.java) { #typed }

## Supervision

The default supervision for untyped actors is to restart whereas for typed it is to stop.
When combining untyped and typed actors the default supervision is based on the default behavior of
the child i.e. if an untyped actor creates a typed child, its default supervision will be to stop. If a typed
actor creates an untyped child, its default supervision will be to restart.



