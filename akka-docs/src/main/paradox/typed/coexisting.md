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
and classic actors together, within the same `ActorSystem`. Also, we will not be able to integrate with all existing modules in one big bang release and that is another reason for why these two ways of writing actors must be able to coexist.

There are two different `ActorSystem`s: `akka.actor.ActorSystem` and `akka.actor.typed.ActorSystem`. 

Currently the typed actor system is implemented using the classic actor system under the hood. This may change in the future.

Typed and classic can interact the following ways:

* classic actor systems can create typed actors
* typed actors can send messages to classic actors, and opposite
* spawn and supervise typed child from classic parent, and opposite
* watch typed from classic, and opposite
* classic actor system can be converted to a typed actor system

@@@ div { .group-scala }
In the examples the `akka.actor` package is aliased to `classic`.

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #import-alias }

@@@

@java[The examples use fully qualified class names for the classic classes to distinguish between typed and classic classes with the same name.]

## Classic to typed 

While coexisting your application will likely still have a classic ActorSystem. This can be converted to a typed ActorSystem
so that new code and migrated parts don't rely on the classic system:

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #adapter-import #convert-classic }

Java
:  @@snip [ClassicWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/ClassicWatchingTypedTest.java) { #adapter-import #convert-classic }

Then for new typed actors here's how you create, watch and send messages to
it from a classic actor.

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #typed }

Java
:  @@snip [ClassicWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/ClassicWatchingTypedTest.java) { #typed }

The top level classic actor is created in the usual way:

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #create-classic }

Java
:  @@snip [ClassicWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/ClassicWatchingTypedTest.java) { #create-classic }

Then it can create a typed actor, watch it, and send a message to it:

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #classic-watch }

Java
:  @@snip [ClassicWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/ClassicWatchingTypedTest.java) { #classic-watch }

@scala[There is one `import` that is needed to make that work.] @java[We import the Adapter class and
call static methods for conversion.]

Scala
:  @@snip [ClassicWatchingTypedSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/ClassicWatchingTypedSpec.scala) { #adapter-import }

Java
:  @@snip [ClassicWatchingTypedTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/ClassicWatchingTypedTest.java) { #adapter-import }


@scala[That adds some implicit extension methods that are added to classic and typed `ActorSystem`, `ActorContext` and `ActorRef` in both directions.]
@java[To convert between typed and classic `ActorSystem`, `ActorContext` and `ActorRef` in both directions there are adapter methods in `akka.actor.typed.javadsl.Adapter`.]
Note the inline comments in the example above.

## Typed to classic

Let's turn the example upside down and first start the typed actor and then the classic as a child.

The following will show how to create, watch and send messages back and forth from a typed actor to this
classic actor:

Scala
:  @@snip [TypedWatchingClassicSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingClassicSpec.scala) { #classic }

Java
:  @@snip [TypedWatchingClassicTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingClassicTest.java) { #classic }

Creating the actor system and the typed actor:

Scala
:  @@snip [TypedWatchingClassicSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingClassicSpec.scala) { #create }

Java
:  @@snip [TypedWatchingClassicTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingClassicTest.java) { #create }

Then the typed actor creates the classic actor, watches it and sends and receives a response:

Scala
:  @@snip [TypedWatchingClassicSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/coexistence/TypedWatchingClassicSpec.scala) { #typed }

Java
:  @@snip [TypedWatchingClassicTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/coexistence/TypedWatchingClassicTest.java) { #typed }

@@@ Note

One important difference when having a typed system and a typed user guardian actor and combining that with classic actors  
is that even if you can turn the typed `ActorSystem` to a classic one it is no longer possible to spawn user level
actors, trying to do this will throw an exception, such usage must instead be replaced with bootstrap directly in the 
guardian actor, or commands telling the guardian to spawn children. 

@@@

## Supervision

The default supervision for classic actors is to restart whereas for typed it is to stop.
When combining classic and typed actors the default supervision is based on the default behavior of
the child, for example if a classic actor creates a typed child, its default supervision will be to stop. If a typed
actor creates a classic child, its default supervision will be to restart.



