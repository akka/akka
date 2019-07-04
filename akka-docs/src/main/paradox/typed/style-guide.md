# Style guide

## Functional vs object-oriented style

There are two flavors of the Actor APIs.

1. The functional programming style where you pass a function to a factory which then constructs a behavior,
  for stateful actors this means passing immutable state around as parameters and switching to a new behavior
  whenever you need to act on a changed state.
1. The object-oriented style where a concrete class for the actor behavior is defined and mutable
  state is kept inside of it as fields.

An example of a counter actor implemented in the functional style:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #fun-style }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #fun-style }

Corresponding actor implemented in the object-oriented style:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #oo-style }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #oo-style }

Some similarities to note:

* Messages are defined in the same way.
* Both have @scala[an `apply` factory method in the companion object]@java[a static `create` factory method] to
  create the initial behavior, i.e. from the outside they are used in the same way.
* @scala[Pattern matching]@java[Matching] and handling of the messages are done in the same way.
* The `ActorContext` API is the same.

A few differences to note:

* There is no class in the functional style, but that is not strictly a requirement and sometimes it's
  convenient to use a class also with the functional style to reduce number of parameters in the methods.
* Mutable state, such as the @scala[`var n`]@java[`int n`] is typically used in the object-oriented style.
* In the functional style the state is is updated by returning a new behavior that holds the new immutable state,
  the @scala[`n: Int`]@java[`final int n`] parameter of the `counter` method.
* The object-oriented style must use a new instance of the initial `Behavior` for each spawned actor instance,
  since the state in `AbstractBehavior` instance must not be shared between actor instances.
  This is "hidden" in the functional style since the immutable state is captured by the function.
* In the object-oriented style one can return `this` to stay with the same behavior for next message.
  In the functional style there is no `this` so `Behviors.same` is used instead.
* @scala[The `ActorContext` is accessed in different ways. In the object-oriented style it's retrieved from
  `Behaviors.setup` and kept as an instance field, while in the functional style it's passed in alongside
  the message. That said, `Behaviors.setup` is often used in the functional style as well, and then
  often together with `Behaviors.receiveMessage` that doesn't pass in the context with the message.]
  @java[The `ActorContext` is accessed with `Behaviors.setup` but then kept in different ways.
  As an instance field vs. a method parameter.]

Which style you choose to use is a matter of taste and both styles can be mixed depending on which is best
for a specific actor. An actor can switch between behaviors implemented in different styles.
For example, it may have an initial behavior that is only stashing messages until some initial query has been
completed and then switching over to its main active behavior that is maintaining some mutable state. Such
initial behavior is nice in the functional style and the active behavior may be better with the
object-oriented style.

We would recommend using the tool that is best for the job. The APIs are similar in many ways to make it
easy to learn both. You may of course also decide to just stick to one style for consistency and
familiarity reasons.

@@@ div {.group-scala}

When developing in Scala the functional style will probably be the choice for many.

Some reasons why you may want to use the functional style:

* You are familiar with a functional approach of structuring the code. Note that this API is still
  not using any advanced functional programming or type theory constructs.
* The state is immutable and can be passed to "next" behavior.
* The `Behavior` is stateless.
* The actor lifecycle has several different phases that can be represented by switching between different
  behaviors, like a finite state machine. This is also supported with the object-oriented style, but
  it's typically nicer with the functional style.
* It's less risk of accessing mutable state in the actor from other threads, like `Future` or Streams
  callbacks.

Some reasons why you may want to use the object-oriented style:

* You are more familiar with an object-oriented style of structuring the code with methods
  in a class rather than functions.
* Some state is not immutable.
* It could be more familiar and easier to migrate existing classic actors to this style.
* Mutable state can sometimes have better performance, e.g. mutable collections and
  avoiding allocating new instance for next behavior (be sure to benchmark if this is your
  motivation).

@@@

@@@ div {.group-java}

When developing in Java the functional style will probably be the choice for many.

Some reasons why you may want to use the object-oriented style:

* You are more familiar with an object-oriented style of structuring the code with methods
  in a class rather than functions.
* Java lambdas can only close over final or effectively final fields, making it
  impractical to use the functional style in behaviors that mutate their fields.
* Some state is not immutable, e.g. immutable collections are not widely used in Java.
  It is OK to use mutable state also with the functional style but you must make sure
  that it's not shared between different actor instances.
* It could be more familiar and easier to migrate existing classic actors to this style.
* Mutable state can sometimes have better performance, e.g. mutable collections and
  avoiding allocating new instance for next behavior (be sure to benchmark if this is your
  motivation).

Some reasons why you may want to use the functional style:

* You are familiar with a functional approach of structuring the code. Note that this API is still
  not using any advanced functional programming or type theory constructs.
* The state is immutable and can be passed to "next" behavior.
* The `Behavior` is stateless.
* The actor lifecycle has several different phases that can be represented by switching between different
  behaviors, like a finite state machine. This is also supported with the object-oriented style, but
  it's typically nicer with the functional style.
* It's less risk of accessing mutable state in the actor from other threads, like `CompletionStage` or Streams
  callbacks.

@@@

