# Typed Actor Interaction Patterns

Interacting with an Actor in Akka Typed is done through an @scala[`ActorRef[T]`]@java[`ActorRef<T>`] where `T` is the type of messages the actor accepts, also known as the "protocol". This ensures that only the right kind of messages can be sent to an actor and also ensures no access to the Actor instance internals is available to anyone else but the Actor itself. 

Message exchange with Actors follow a few common patterns, let's go through each one of them. 

## Fire and Forget

The fundamental way to interact with an actor is through @scala["tell", which is so common that it has a special symbolic method name: `actorRef ! message`]@java[`actorRef.tell(message)`]. Sending a message to an actor like this can be done both from inside another actor and from any logic outside of the `ActorSystem`.

Tell is asynchronous which means that the method returns right away and that when execution of the statement after it in the code is executed there is no guarantee that the message has been processed by the recipient yet. It also means there is no way to way to know if the processing succeeded or failed without additional interaction with the actor in question.

Scala
:  @@snip [InteractionPatternsSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/InteractionPatternsSpec.scala) { #fire-and-forget }

Java
:  @@snip [InteractionPatternsTest.java]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/InteractionPatternsTest.java) { #fire-and-forget }

**Scenarios fire and forget is useful:**

 * When it is not critical to be sure that the message was processed
 * When there is no way to act on non successful delivery or processing
 * When we want to minimize the number of messages created to get higher throughput

**Problems with fire and forget:**

 * Consistently higher rates of fire and forget to an actor than it process will make the inbox fill up and can in the worst case cause the JVM crash with an `OutOfMemoryError`
 * If the message got lost, we will not notice

## Same protocol Request-Response

In many interactions a request is followed by a response back from the actor. In Akka Typed the recipient of responses has to be encoded as a field in the message itself, which the recipient can then use to send a response back. When the response message is already a part of the sending actor protocol we can simply use @scala[`ActorContext.self`]@java[`ActorContext.getSelf()`] when constructing the message.

TODO sample

**Scenarios where request response with tell is useful:**

 * Subscribing to an actor that will send many response messages (of the same protocol) back
 * When communicating between a parent and its children, where the protocol can be made include the messages for the interaction 
 * ???

**Problems request-response:**

 * Often the response that the other actor wants to send back is not a part of the sending actor's protocol (see adapted request response or ask)
 * It is hard to detect and that a message request was not delivered or processed (see ask)
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the response, it is not possible to tie an interaction to some specific context without introducing a new, separate, actor

## Adapted Request-Response

Very often the receiving does not, and should not be made, know of the protocol of the sending actor, and will respond with one or more messages that the sending actor cannot receive.

TODO sample

**Scenarios where Adapted Request-Response is useful:**

 * Subscribing to an actor that will send many response messages back 
 
**Problems with adapted request-response:**

 * It is hard to detect and that a message request was not delivered or processed (see ask)
 * Only one adaption can be made per response message type, if a new one is registered the old one is replaced, for example different target actors can't have different adaption if they use the same response types, unless some correlation is encoded in the messages
 * Unless the protocol already includes a way to provide context, for example a request id that is also sent in the response, it is not possible to tie an interaction to some specific context without introducing a new, separate, actor 

 
## 1:1 Request-Response with ask between two actors
 
In an interaction where there is a 1:1 mapping between a request and a response we can use `ask` on the `ActorContext` to interact with another actor.

The interaction has two steps, first we need to construct the outgoing message, to do that we need an @scala[`ActorRef[Response]`]@java[`ActorRef<Response>`] to put as recipient in the outgoing message. The second step is to transform the `Response` or the failure to produce a response, into a message that is part of the protocol of the sending actor.

TODO sample


**Scenarios where ask is useful:**

 * Single response queries
 * When an actor needs to know that the message was processed before continuing 
 * To allow an actor to resend if a timely response is not produced
 * To keep track of outstanding requests and not overwhelm a recipient with messages (simple backpressure)
 * When some context should be attached to the interaction but the protocol does not support that (request id, what query the response was for)
 
**Problems with ask:**

 * There can only be a single response to one `ask`
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact
 * Finding a good value for the timeout, especially when `ask` is triggers chained `ask`s in the receiving actor. You want a short timeout to be responsive and answer back to the requestor, but at the same time you do not want to have many false positives 


## 1:1 Request-Response with ask from outside the ActorSystem

In an interaction where there is a 1:1 mapping between a request and a response we can use @scala[`ActorRef.?` implicitly provided by `akka.actor.typed.scaladsl.AskPattern`]@java[`akka.actor.typed.javadsl.AskPattern.ask`] to send a message to an actor and get a @scala[`Future[Response]`]@java[`CompletionState[Response]`] back.

TODO sample

**Scenarios where this ask variant is useful:**

 * Single response queries where the response should be passed on to some other actor
 * ???

**Problems with ask:**

 * There can only be a single response to one `ask`
 * When `ask` times out, the receiving actor does not know and may still process it to completion, or even start processing it after the fact


## Per session child Actor

Keeping context for an interaction, or multiple interactions can be done by moving the work for one "session", into a child actor.

TODO

**Scenarios where per session child actor is useful:**

 * A single incoming request should result in multiple interactions with other actions before a result can be built
 * ???

**Problems with ask:**

 * Children have lifecycles that must be managed to not create a resource leak
 * ???