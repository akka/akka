# Interaction Patterns

## Dependency

To use Akka Actor Typed, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

Interacting with an Actor in Akka Typed is done through an @scala[`ActorRef[T]`]@java[`ActorRef<T>`] where `T` is the type of messages the actor accepts, also known as the "protocol". This ensures that only the right kind of messages can be sent to an actor and also that no one else but the Actor itself can access the Actor instance internals.

Message exchange with Actors follow a few common patterns, let's go through each one of them. 

## Fire and Forget

The fundamental way to interact with an actor is through @scala["tell", which is so common that it has a special symbolic method name: `actorRef ! message`]@java[`actorRef.tell(message)`]. Sending a message with tell can safely be done from any thread.

Tell is asynchronous which means that the method returns right away, when the statement after it is executed there is no guarantee that the message has been processed by the recipient yet. It also means there is no way to know if the message was received, the processing succeeded or failed.

With the given protocol and actor behavior:

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #fire-and-forget-definition }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #fire-and-forget-definition }


Fire and forget looks like this:

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #fire-and-forget-doit }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #fire-and-forget-doit }


**Useful when:**

 * It is not critical to be sure that the message was processed
 * There is no way to act on non successful delivery or processing
 * We want to minimize the number of messages created to get higher throughput (sending a response would require creating twice the number of messages)

**Problems:**

 * If the inflow of messages is higher than the actor can process the inbox will fill up and can in the worst case cause the JVM crash with an `OutOfMemoryError`
 * If the message gets lost, the sender will not know

## Request-Response

Many interactions between actors requires one or more response message being sent back from the receiving actor. A response message can be a result of a query, some form of acknowledgment that the message was received and processed or events that the request subscribed to. 

In Akka Typed the recipient of responses has to be encoded as a field in the message itself, which the recipient can then use to send (tell) a response back.

With the following protocol:

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-protocol }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-protocol }


The sender would use its own @scala[`ActorRef[Response]`]@java[`ActorRef<Response>`], which it can access through @scala[`ActorContext.self`]@java[`ActorContext.getSelf()`], for the `respondTo`. 

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-send }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-send }


On the receiving side the @scala[`ActorRef[response]`]@java[`ActorRef<Response>`] can then be used to send one or more responses back:

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-respond }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-respond }


**Useful when:**

 * Subscribing to an actor that will send many response messages back
 
**Problems:**

 * Actors seldom have a response message from another actor as a part of their protocol (see @ref:[adapted response](#adapted-response))
 * It is hard to detect that a message request was not delivered or processed (see @ref:[ask](#request-response-with-ask-between-two-actors))
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the
   response, it is not possible to tie an interaction to some specific context without introducing a new,
   separate, actor (see ask or per session child actor)


## Adapted Response

Most often the sending actor does not, and should not, support receiving the response messages of another actor. In such cases we need to provide an `ActorRef` of the right type and adapt the response message to a type that the sending actor can handle.

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #adapted-response }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #adapted-response }

You can register several message adapters for different message classes.
It's only possible to have one message adapter per message class to make sure
that the number of adapters are not growing unbounded if registered repeatedly.
That also means that a registered adapter will replace an existing adapter for
the same message class.

A message adapter will be used if the message class matches the given class or
is a subclass thereof. The registered adapters are tried in reverse order of
their registration order, i.e. the last registered first.

A message adapter (and the returned `ActorRef`) has the same lifecycle as
the receiving actor. It's recommended to register the adapters in a top level
`Behaviors.setup` or constructor of `AbstractBehavior` but it's possible to
register them later also if needed.

The adapter function is running in the receiving actor and can safely access state of it, but if it throws an exception the actor is stopped.

**Useful when:**

 * Translating between different actor message protocols
 * Subscribing to an actor that will send many response messages back
 
**Problems:**

 * It is hard to detect that a message request was not delivered or processed (see @ref:[ask](#request-response-with-ask-between-two-actors))
 * Only one adaption can be made per response message type, if a new one is registered the old one is replaced,
   for example different target actors can't have different adaption if they use the same response types, unless some
   correlation is encoded in the messages
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the
   response, it is not possible to tie an interaction to some specific context without introducing a new,
   separate, actor

 
## Request-Response with ask between two actors
 
In an interaction where there is a 1:1 mapping between a request and a response we can use `ask` on the `ActorContext` to interact with another actor.

The interaction has two steps, first we need to construct the outgoing message, to do that we need an @scala[`ActorRef[Response]`]@java[`ActorRef<Response>`] to put as recipient in the outgoing message. The second step is to transform the successful `Response` or failure into a message that is part of the protocol of the sending actor.

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #actor-ask }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #actor-ask }


The response adapting function is running in the receiving actor and can safely access state of it, but if it throws an exception the actor is stopped.

**Useful when:**

 * Single response queries
 * An actor needs to know that the message was processed before continuing 
 * To allow an actor to resend if a timely response is not produced
 * To keep track of outstanding requests and not overwhelm a recipient with messages ("backpressure")
 * Context should be attached to the interaction but the protocol does not support that (request id, what query the response was for)
 
**Problems:**

 * There can only be a single response to one `ask` (see @ref:[per session child Actor](#per-session-child-actor))
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact
 * Finding a good value for the timeout, especially when `ask` is triggers chained `ask`s in the receiving actor. You want a short timeout to be responsive and answer back to the requester, but at the same time you do not want to have many false positives 

<a id="outside-ask"></a>
## Request-Response with ask from outside an Actor

Some times you need to interact with actors from outside of the actor system, this can be done with fire-and-forget as described above or through another version of `ask` that returns a @scala[`Future[Response]`]@java[`CompletionStage<Response>`] that is either completed with a successful response or failed with a `TimeoutException` if there was no response within the specified timeout.
 
To do this we use @scala[`ActorRef.ask` (or the symbolic `ActorRef.?`) implicitly provided by `akka.actor.typed.scaladsl.AskPattern`]@java[`akka.actor.typed.javadsl.AskPattern.ask`] to send a message to an actor and get a @scala[`Future[Response]`]@java[`CompletionState[Response]`] back.

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #standalone-ask }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #standalone-ask }

**Useful when:**

 * Querying an actor from outside of the actor system 

**Problems:**

 * It is easy to accidentally close over and unsafely mutable state with the callbacks on the returned @scala[`Future`]@java[`CompletionStage`] as those will be executed on a different thread
 * There can only be a single response to one `ask` (see @ref:[per session child Actor](#per-session-child-actor))
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact


## Per session child Actor

In some cases a complete response to a request can only be created and sent back after collecting multiple answers from other actors. For these kinds of interaction it can be good to delegate the work to a per "session" child actor. The child could also contain arbitrary logic to implement retrying, failing on timeout, tail chopping, progress inspection etc.

Note that this in fact essentially how `ask` is implemented, if all you need is a single response with a timeout it is better to use `ask`.

The child is created with the context it needs to do the work, including an `ActorRef` that it can respond to. When the complete result is there the child responds with the result and stops itself.

As the protocol of the session actor is not a public API but rather an implementation detail of the parent actor, it may not always make sense to have an explicit protocol and adapt the messages of the actors that the session actor interacts with. For this use case it is possible to express that the actor can receive any message (@scala[`Any`]@java[`Object`]).

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #per-session-child }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #per-session-child }

In an actual session child you would likely want to include some form of timeout as well (see @ref:[scheduling messages to self](#scheduling-messages-to-self)).

**Useful when:**

 * A single incoming request should result in multiple interactions with other actors before a result can be built,
   for example aggregation of several results
 * You need to handle acknowledgement and retry messages for at-least-once delivery

**Problems:**

 * Children have life cycles that must be managed to not create a resource leak, it can be easy to miss a scenario where the session actor is not stopped
 * It increases complexity, since each such child can execute concurrently with other children and the parent
 
## Scheduling messages to self

The following example demonstrates how to use timers to schedule messages to an actor. 

The `Buncher` actor buffers a burst of incoming messages and delivers them as a batch after a timeout or when the number of batched messages exceeds a maximum size.

Scala
:  @@snip [InteractionPatternsSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #timer }

Java
:  @@snip [InteractionPatternsTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #timer }

There are a few things worth noting here:

* To get access to the timers you start with `Behaviors.withTimers` that will pass a `TimerScheduler` instance to the function. 
This can be used with any type of `Behavior`, including `receive`, `receiveMessage`, but also `setup` or any other behavior.
* Each timer has a key and if a new timer with same key is started the previous is cancelled and it's guaranteed that a message from the previous timer is not received, even though it might already be enqueued in the mailbox when the new timer is started.
* Both periodic and single message timers are supported. 
* The `TimerScheduler` is mutable in itself, because it performs and manages the side effects of registering the scheduled tasks.
* The `TimerScheduler` is bound to the lifecycle of the actor that owns it and it's cancelled automatically when the actor is stopped.
* `Behaviors.withTimers` can also be used inside `Behaviors.supervise` and it will automatically cancel the started timers correctly when the actor is restarted, so that the new incarnation will not receive scheduled messages from previous incarnation.

### Schedule periodically

Scheduling of recurring messages can have two different characteristics:

* fixed-delay - The delay between sending subsequent messages will always be (at least) the given `delay`.
  Use `startTimerWithFixedDelay`.
* fixed-rate - The frequency of execution over time will meet the given `interval`. Use `startTimerAtFixedRate`.

If you are uncertain of which one to use you should pick `startTimerWithFixedDelay`.

When using **fixed-delay** it will not compensate the delay between messages if the scheduling is delayed longer
than specified for some reason. The delay between sending subsequent messages will always be (at least) the given
`delay`. In the long run, the frequency of messages will generally be slightly lower than the reciprocal of the
specified `delay`.

Fixed-delay execution is appropriate for recurring activities that require "smoothness." In other words,
it is appropriate for activities where it is more important to keep the frequency accurate in the short run
than in the long run.

When using **fixed-rate** it will compensate the delay for a subsequent task if the previous messages were delayed
too long. In such cases, the actual sending interval will differ from the interval passed to the `scheduleAtFixedRate`
method.

If the tasks are delayed longer than the `interval`, the subsequent message will be sent immediately after the
prior one. This also has the consequence that after long garbage collection pauses or other reasons when the JVM
was suspended all "missed" tasks will execute when the process wakes up again. For example, `scheduleAtFixedRate`
with an interval of 1 second and the process is suspended for 30 seconds will result in 30 messages being sent
in rapid succession to catch up. In the long run, the frequency of execution will be exactly the reciprocal of
the specified `interval`.

Fixed-rate execution is appropriate for recurring activities that are sensitive to absolute time
or where the total time to perform a fixed number of executions is important, such as a countdown
timer that ticks once every second for ten seconds.

@@@ warning

`scheduleAtFixedRate` can result in bursts of scheduled messages after long garbage collection pauses,
which may in worst case cause undesired load on the system. `scheduleWithFixedDelay` is often preferred.

@@@


