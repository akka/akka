# Interaction Patterns

Interacting with an Actor in Akka Typed is done through an @scala[`ActorRef[T]`]@java[`ActorRef<T>`] where `T` is the type of messages the actor accepts, also known as the "protocol". This ensures that only the right kind of messages can be sent to an actor and also ensures no access to the Actor instance internals is available to anyone else but the Actor itself. 

Message exchange with Actors follow a few common patterns, let's go through each one of them. 

## Fire and Forget

The fundamental way to interact with an actor is through @scala["tell", which is so common that it has a special symbolic method name: `actorRef ! message`]@java[`actorRef.tell(message)`]. Sending a message to an actor like this can be done both from inside another actor and from any logic outside of the `ActorSystem`.

Tell is asynchronous which means that the method returns right away and that when execution of the statement after it in the code is executed there is no guarantee that the message has been processed by the recipient yet. It also means there is no way to way to know if the processing succeeded or failed without additional interaction with the actor in question.

With the given protocol and actor behavior:

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #fire-and-forget-definition }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #fire-and-forget-definition }


Fire and forget looks like this:

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #fire-and-forget-doit }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #fire-and-forget-doit }


**Useful when:**

 * It is not critical to be sure that the message was processed
 * There is no way to act on non successful delivery or processing
 * We want to minimize the number of messages created to get higher throughput

**Problems:**

 * Consistently higher rates of fire and forget to an actor than it process will make the inbox fill up and can in the worst case cause the JVM crash with an `OutOfMemoryError`
 * If the message got lost, we will not notice

## Request-Response

In many interactions between actors a message leads to a response message back being sent back from the other actor, it can be the result of a query or some form of acknowledgment that the message was received and processed. In Akka Typed the recipient of responses has to be encoded as a field in the message itself, which the recipient can then use to send a response back. 

With the following protocol:

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-protocol }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-protocol }


The sender would use its own @scala[`ActorRef[Response]`]@java[`ActorRef<Response>`], which it can access through @scala[`ActorContext.self`]@java[`ActorContext.getSelf()`], for the `respondTo`. 

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-send }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-send }


On the receiving side the `ActorRef` can then be used to send one or more responses back:

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #request-response-respond }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #request-response-respond }


**Useful when:**

 * Subscribing to an actor that will send many response messages back
 * Translating between different actor message protocols
 
**Problems:**

 * Actors seldom have a response message from another actor as a part of their protocol (see adapted response)
 * It is hard to detect that a message request was not delivered or processed (see ask)
 * Only one adaption can be made per response message type, if a new one is registered the old one is replaced,
   for example different target actors can't have different adaption if they use the same response types, unless some
   correlation is encoded in the messages
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the
   response, it is not possible to tie an interaction to some specific context without introducing a new,
   separate, actor


## Adapted Response

Most often the receiving actor does not, and should, know of the protocol of the sending actor, and
will respond with one or more messages that the sending actor cannot receive.

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #adapted-response }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #adapted-response }

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
`Behaviors.deferred` or constructor of `MutableBehavior` but it's possible to
register them later also if needed.

The function is running in the receiving actor and can safely access state of it.

**Useful when:**

 * Subscribing to an actor that will send many response messages back
 * Translating between different actor message protocols
 
**Problems:**

 * It is hard to detect that a message request was not delivered or processed (see ask)
 * Only one adaption can be made per response message type, if a new one is registered the old one is replaced,
   for example different target actors can't have different adaption if they use the same response types, unless some
   correlation is encoded in the messages
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the
   response, it is not possible to tie an interaction to some specific context without introducing a new,
   separate, actor

 
## 1:1 Request-Response with ask between two actors
 
In an interaction where there is a 1:1 mapping between a request and a response we can use `ask` on the `ActorContext` to interact with another actor.

The interaction has two steps, first we need to construct the outgoing message, to do that we need an @scala[`ActorRef[Response]`]@java[`ActorRef<Response>`] to put as recipient in the outgoing message. The second step is to transform the `Response` or the failure to produce a response, into a message that is part of the protocol of the sending actor.

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #actor-ask }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #actor-ask }


The function is running in the receiving actor and can safely access state of it.

**Useful when:**

 * Single response queries
 * When an actor needs to know that the message was processed before continuing 
 * To allow an actor to resend if a timely response is not produced
 * To keep track of outstanding requests and not overwhelm a recipient with messages (simple backpressure)
 * When some context should be attached to the interaction but the protocol does not support that (request id, what query the response was for)
 
**Problems:**

 * There can only be a single response to one `ask`
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact
 * Finding a good value for the timeout, especially when `ask` is triggers chained `ask`s in the receiving actor. You want a short timeout to be responsive and answer back to the requestor, but at the same time you do not want to have many false positives 


## 1:1 Request-Response with ask from outside the ActorSystem

Some times you need to interact with actors from outside of the actor system, this can be done with fire-and-forget as described above or through another version of `ask` that returns a @scala[`Future[Response]`]@java[`CompletionStage<Response>`] that is either completed with a succesful response or failed with a `TimeoutException` if there was no response within the specified timeout. 
 
To do this we use @scala[`ActorRef.ask` (or the symbolic `ActorRef.?`) implicitly provided by `akka.actor.typed.scaladsl.AskPattern`]@java[`akka.actor.typed.javadsl.AskPattern.ask`] to send a message to an actor and get a @scala[`Future[Response]`]@java[`CompletionState[Response]`] back.

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #standalone-ask }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #standalone-ask }

**Useful when:**

 * Querying an actor about something from outside of the actor system 

**Problems:**

 * It is easy to accidentally close over and unsafely mutable state with the callbacks on the returned @scala[`Future`]@java[`CompletionStage`]
 * There can only be a single response to one `ask`
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact


## Per session child Actor

Keeping context for an interaction, or multiple interactions can be done by moving the work for one "session", into a child actor.

TODO

**Useful when:**

 * A single incoming request should result in multiple interactions with other actors before a result can be built,
   for example aggregation of several results
 * Handle acknowledgement and retry messages for at-least-once delivery
 * ???

**Problems:**

 * Children have lifecycles that must be managed to not create a resource leak
 * ???
 
## Scheduling messages to self

The following example demonstrates how to use timers to schedule messages to an actor. 

The `Buncher` actor buffers a burst of incoming messages and delivers them as a batch after a timeout or when the number of batched messages exceeds a maximum size.

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #timer }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #timer }

There are a few things worth noting here:

* To get access to the timers you start with `Behaviors.withTimers` that will pass a `TimerScheduler` instance to the function. This can be used with any type of `Behavior`, such as `immutable` or `mutable`.
* Each timer has a key and if a new timer with same key is started the previous is cancelled and it's guaranteed that a message from the previous timer is not received, even though it might already be enqueued in the mailbox when the new timer is started.
* Both periodic and single message timers are supported. 
* The `TimerScheduler` is mutable in itself, because it performs and manages the side effects of registering the scheduled tasks.
* The `TimerScheduler` is bound to the lifecycle of the actor that owns it and it's cancelled automatically when the actor is stopped.
* `Behaviors.withTimers` can also be used inside `Behaviors.supervise` and it will automatically cancel the started timers correctly when the actor is restarted, so that the new incarnation will not receive scheduled messages from previous incarnation.



