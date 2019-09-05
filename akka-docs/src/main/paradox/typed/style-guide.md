# Style guide

This is a style guide with recommendations of idioms and pattern for writing Akka Typed actors.

As with all style guides, treat this as a list of rules to be broken. There are certainly times
when alternative styles should be preferred over the ones given here.

## Functional versus object-oriented style

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
  In the functional style there is no `this` so `Behaviors.same` is used instead.
* @scala[The `ActorContext` is accessed in different ways. In the object-oriented style it's retrieved from
  `Behaviors.setup` and kept as an instance field, while in the functional style it's passed in alongside
  the message. That said, `Behaviors.setup` is often used in the functional style as well, and then
  often together with `Behaviors.receiveMessage` that doesn't pass in the context with the message.]
  @java[The `ActorContext` is accessed with `Behaviors.setup` but then kept in different ways.
  As an instance field versus a method parameter.]

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
  behaviors, like a @ref:[finite state machine](fsm.md). This is also supported with the object-oriented style, but
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

When developing in Java the object-oriented style will probably be the choice for many.

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
  behaviors, like a @ref:[finite state machine](fsm.md). This is also supported with the object-oriented style, but
  it's typically nicer with the functional style.
* It's less risk of accessing mutable state in the actor from other threads, like `CompletionStage` or Streams
  callbacks.

@@@

## Passing around too many parameters

One thing you will quickly run into when using the functional style is that you need to pass around many parameters.

Let's add `name` parameter and timers to the previous `Counter` example. A first approach would be to just add those
as separate parameters:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #fun-style-setup-params1 }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #fun-style-setup-params1 }

Ouch, that doesn't look good. More things may be needed, such as stashing or application specific "constructor"
parameters. As you can imagine, that will be too much boilerplate.

As a first step we can place all these parameters in a class so that we at least only have to pass around one thing.
Still good to have the "changing" state, the @scala[`n: Int`]@java[`final int n`] here, as a separate parameter.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #fun-style-setup-params2 }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #fun-style-setup-params2 }

That's better. Only one thing to carry around and easy to add more things to it without rewriting everything.
@scala[Note that we also placed the `ActorContext` in the `Setup` class, and therefore switched from
`Behaviors.receive` to `Behaviors.receiveMessage` since we already have access to the `context`.]

It's still rather annoying to have to pass the same thing around everywhere.

We can do better by introducing an enclosing class, even though it's still using the functional style.
The "constructor" parameters can be @scala[immutable]@java[`final`] instance fields and can be accessed from
member methods.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #fun-style-setup-params3 }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #fun-style-setup-params3 }

That's nice. One thing to be cautious with here is that it's important that you create a new instance for
each spawned actor, since those parameters must not be shared between different actor instances. That comes natural
when creating the instance from `Behaviors.setup` as in the above example. Having a
@scala[`apply` factory method in the companion object and making the constructor private is recommended.]
@java[static `create` factory method and making the constructor private is recommended.]

This can also be useful when testing the behavior by creating a test subclass that overrides certain methods in the
class. The test would create the instance without the @scala[`apply` factory method]@java[static `create` factory method].
Then you need to relax the visibility constraints of the constructor and methods.

It's not recommended to place mutable state and @scala[`var` members]@java[non-final members] in the enclosing class.
It would be correct from an actor thread-safety perspective as long as the same instance of the enclosing class
is not shared between different actor instances, but if that is what you need you should rather use the
object-oriented style with the `AbstractBehavior` class.

@@@ div {.group-scala}

Similar can be achieved without an enclosing class by placing the `def counter` inside the `Behaviors.setup`
block. That works fine, but for more complex behaviors it can be better to structure the methods in a class.
For completeness, here is how it would look like:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #fun-style-setup-params4 }

@@@

## Behavior factory method

The initial behavior should be created via @scala[a factory method in the companion object]@java[a static factory method].
Thereby the usage of the behavior doesn't change when the implementation is changed, for example if
changing between object-oriented and function style.

The factory method is a good place for retrieving resources like `Behaviors.withTimers`, `Behaviors.withStash`
and `ActorContext` with `Behaviors.setup`.

When using the object-oriented style, `AbstractBehavior`, a new instance should be created from a `Behaviors.setup`
block in this factory method even though the `ActorContext` is not needed.  This is important because a new
instance should be created when restart supervision is used. Typically, the `ActorContext` is needed anyway.

The naming convention for the factory method is @scala[`apply` (when using Scala)]@java[`create` (when using Java)].
Consistent naming makes it easier for readers of the code to find the "starting point" of the behavior.

In the functional style the factory could even have been defined as a @scala[`val`]@java[`static field`]
if all state is immutable and captured by the function, but since most behaviors need some initialization
parameters it is preferred to consistently use a method @scala[(`def`)] for the factory.

Example:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #behavior-factory-method }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #behavior-factory-method }

When spawning an actor from this initial behavior it looks like:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #behavior-factory-method-spawn }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #behavior-factory-method-spawn }


## Where to define messages

When sending or receiving actor messages they should be prefixed with the name
of the actor/behavior that defines them to avoid ambiguities.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #message-prefix-in-tell }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #message-prefix-in-tell }

Such a style is preferred over using @scala[importing `Down` and using `countDown ! Down`]
@java[importing `Down` and using `countDown.tell(Down.INSTANCE);`].
However, within the `Behavior` that handle these messages the short names can be used.

Therefore it is not recommended to define messages as top-level classes.

For the majority of cases it's good style to define
the messages @scala[in the companion object]@java[as static inner classes] together with the `Behavior`.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #messages }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #messages }

If several actors share the same message protocol, it's recommended to define
those messages in a separate @scala[`object`]@java[`interface`] for that protocol.

Here's an example of a shared message protocol setup:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #message-protocol }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #message-protocol }

## Public versus private messages

Often an actor has some messages that are only for its internal implementation and not part of the public
message protocol, such as timer messages or wrapper messages for `ask` or `messageAdapter`.

Such messages should be declared `private` so they can't be accessed
and sent from the outside of the actor. Note that they must still @scala[extend]@java[implement] the
public `Command` @scala[trait]@java[interface].

Here is an example of using `private` for an internal message:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #public-private-messages-1 }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #public-private-messages-1 }

An alternative approach is using a type hierarchy and `narrow` to have a super-type for the public messages as a
distinct type from the super-type of all actor messages.  The
former approach is recommended but it is good to know this alternative as it can be useful when
using shared message protocol classes as described in @ref:[Where to define messages](#where-to-define-messages).

Here's an example of using a type hierarchy to separate public and private messages:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #public-private-messages-2 }

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #public-private-messages-2 }

`private` visibility can be defined for the `PrivateCommand` messages but it's not strictly needed since they can't be
sent to an @scala[ActorRef[Command]]@java[ActorRef<Command>], which is the public message type of the actor.

@@@ div {.group-java}

### Singleton messages

For messages without parameters the `enum` singleton pattern is recommended:

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #message-enum }

In the `ReceiveBuilder` it can be matched in same way as other messages:

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #message-enum-match }

@@@

@@@ div {.group-java}

## Lamdas versus method references

It's recommended to keep the message matching with the `ReceiveBuilder` as short and clean as possible
and delegate to methods. This improves readability and ease of method navigation with an IDE.

The delegation can be with lambdas or [method references](https://docs.oracle.com/javase/tutorial/java/javaOO/methodreferences.html).

Example of delegation using a lambda:

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #on-message-lambda }

When possible it's preferred to use method references instead of lambdas. The benefit is less verbosity and
in some cases it can actually give better type inference.

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #on-message-method-ref }

`this::onGetValue` is a method reference in above example. It corresponds to `command -> onGetValue(command)`.

If you are using IntelliJ IDEA it has support for converting lambdas to method references.

More important than the choice between lambdas or method references is to avoid lambdas with a large block of code.
An anti-pattern would be to inline all message handling inside the lambdas like this:

Java
:  @@snip [StyleGuideDocExamples.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/StyleGuideDocExamples.java) { #on-message-lambda-anti }

In a real application it would often be more than 3 lines for each message.
It's not only making it more difficult to get an overview of the message matching, but compiler errors related
to lambdas can sometimes be difficult to understand.

Ideally, lambdas should be written in one line of code. Two lines can be ok, but three is probably too much.
Also, don't use braces and return statements in one-line lambda bodies.

@@@

@@@ div {.group-scala}

## Partial versus total Function

It's recommended to use a `sealed` trait as the super type of the commands (incoming messages) of an actor
as the compiler will emit a warning if a message type is forgotten in the pattern match.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #messages-sealed }

That is the main reason for `Behaviors.receive`, `Behaviors.receiveMessage` taking a `Function` rather than a `PartialFunction`.

The compiler warning if `GetValue` is not handled would be:

```
[warn] ... Counter.scala:45:34: match may not be exhaustive.
[warn] It would fail on the following input: GetValue(_)
[warn]         Behaviors.receiveMessage {
[warn]                                  ^
```

Note that a `MatchError` will be thrown at runtime if a message is not handled, so it's important to pay
attention to those. If a `Behavior` should not handle certain messages you can still include them
in the pattern match and return `Behaviors.unhandled`.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #pattern-match-unhandled }

One thing to be aware of is the exhaustiveness check is not enabled when there is a guard condition in any of the
pattern match cases.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #pattern-match-guard }

Therefore, for the purposes of exhaustivity checking, it is be better to not use guards and instead move the `if`s after the `=>`.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #pattern-match-without-guard }

It's recommended to use the `sealed` trait and total functions with exhaustiveness check to detect mistakes
of forgetting to handle some messages. Sometimes that can be inconvenient and then you can use a `PartialFunction`
with `Behaviors.receivePartial` or `Behaviors.receiveMessagePartial`

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #pattern-match-partial }

@@@

@@@ div {.group-scala}

## ask versus ?

When using the `AskPattern` it's recommended to use the `ask` method rather than the infix `?` operator, like so:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #ask-1 }

You may also use the more terse placeholder syntax `_` instead of `replyTo`:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #ask-2 }

However, using the infix operator `?` with the placeholder syntax `_`, like is done in the following example, won't typecheck because of the binding scope rules for wildcard parameters:

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #ask-3 }

Adding the necessary parentheses (as shown below) makes it typecheck, but, subjectively, it's rather ugly so the recommendation is to use `ask`.

Scala
:  @@snip [StyleGuideDocExamples.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/StyleGuideDocExamples.scala) { #ask-4 }

Note that `AskPattern` is only intended for request-response interaction from outside an actor. If the requester is
inside an actor, prefer `ActorContext.ask` as it provides better thread-safety by not requiring the use of a @scala[`Future`]@java[`CompletionStage`] inside the actor.

@@@

## Additional naming conventions

Some naming conventions have already been mentioned in the context of other recommendations, but here
is a list of additional conventions:

* `replyTo` is the typical name for the @scala[`ActorRef[Reply]`]@java[`ActorRef<Reply>`] parameter in
  messages to which a reply or acknowledgement should be sent.

* Incoming messages to an actor are typically called commands, and therefore the super type of all
  messages that an actor can handle is typically @scala[`sealed trait Command`]@java[`interface Command {}`].

* Use past tense for the events persisted by an `EventSourcedBehavior` since those represent facts that have happened,
  for example `Incremented`.
