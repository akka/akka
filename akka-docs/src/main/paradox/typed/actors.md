# Actors

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.

@@@

## Dependency

To use Akka Actor Typed add the following dependency:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-actor-typed_2.12
  version=$akka.version$
}

## Introduction

As discussed in @ref:[Actor Systems](../general/actor-systems.md) Actors are about
sending messages between independent units of computation, but how does that
look like?

In all of the following these imports are assumed:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #imports }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #imports }

With these in place we can define our first Actor, and of course it will say
hello!

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world-actor }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world-actor }

This small piece of code defines two message types, one for commanding the
Actor to greet someone and one that the Actor will use to confirm that it has
done so. The `Greet` type contains not only the information of whom to
greet, it also holds an `ActorRef` that the sender of the message
supplies so that the `HelloWorld` Actor can send back the confirmation
message.

The behavior of the Actor is defined as the `greeter` value with the help
of the `receive` behavior factory. Processing the next message then results
in a new behavior that can potentially be different from this one. State is
updated by returning a new behavior that holds the new immutable state. In this
case we don't need to update any state, so we return `same`, which means
the next behavior is "the same as the current one".

The type of the messages handled by this behavior is declared to be of class
`Greet`, meaning that `msg` argument is
also typed as such. This is why we can access the `whom` and `replyTo`
members without needing to use a pattern match.

On the last line we see the `HelloWorld` Actor send a message to another
Actor, which is done using the @scala[`!` operator (pronounced “bang” or “tell”).]@java[`tell` method.]
Since the `replyTo` address is declared to be of type @scala[`ActorRef[Greeted]`]@java[`ActorRef<Greeted>`], the
compiler will only permit us to send messages of this type, other usage will
not be accepted.

The accepted message types of an Actor together with all reply types defines
the protocol spoken by this Actor; in this case it is a simple request–reply
protocol but Actors can model arbitrarily complex protocols when needed. The
protocol is bundled together with the behavior that implements it in a nicely
wrapped scope—the `HelloWorld` @scala[object]@java[class].

As Carl Hewitt said, one Actor is no Actor—it would be quite lonely with
nobody to talk to. We need another Actor that that interacts with the `greeter`.
Let's make a `bot` that receives the reply from the `greeter` and sends a number
of additional greeting messages and collect the replies until a given max number
of messages have been reached.

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world-bot }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world-bot }

Note how this Actor manages the counter by changing the behavior for each `Greeted` reply
rather than using any variables.



A third actor spawns the `greeter` and the `bot` and starts the interaction between those.

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world-main }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world-main }

Now we want to try out this Actor, so we must start an ActorSystem to host it:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #hello-world }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #hello-world }

We start an Actor system from the defined `main` behavior and send two `Start` messages that
will kick-off the interaction between two separate `bot` actors and the single `greeter` actor.

The console output may look like this:

```
[INFO] [03/13/2018 15:50:05.814] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/greeter] Hello World!
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/greeter] Hello Akka!
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-2] [akka://hello/user/World] Greeting 1 for World
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/Akka] Greeting 1 for Akka
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-5] [akka://hello/user/greeter] Hello World!
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-5] [akka://hello/user/greeter] Hello Akka!
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/World] Greeting 2 for World
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-5] [akka://hello/user/greeter] Hello World!
[INFO] [03/13/2018 15:50:05.815] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/Akka] Greeting 2 for Akka
[INFO] [03/13/2018 15:50:05.816] [hello-akka.actor.default-dispatcher-5] [akka://hello/user/greeter] Hello Akka!
[INFO] [03/13/2018 15:50:05.816] [hello-akka.actor.default-dispatcher-4] [akka://hello/user/World] Greeting 3 for World
[INFO] [03/13/2018 15:50:05.816] [hello-akka.actor.default-dispatcher-6] [akka://hello/user/Akka] Greeting 3 for Akka
```

## A More Complex Example

The next example is more realistic and demonstrates some important patterns:

* Using a sealed trait and case class/objects to represent multiple messages an actor can receive
* Handle sessions by using child actors
* Handling state by changing behavior
* Using multiple typed actors to represent different parts of a protocol in a type safe way

Consider an Actor that runs a chat room: client Actors may connect by sending
a message that contains their screen name and then they can post messages. The
chat room Actor will disseminate all posted messages to all currently connected
client Actors. The protocol definition could look like the following:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #chatroom-protocol }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #chatroom-protocol }

Initially the client Actors only get access to an @scala[`ActorRef[GetSession]`]@java[`ActorRef<GetSession>`]
which allows them to make the first step. Once a client’s session has been
established it gets a `SessionGranted` message that contains a `handle` to
unlock the next protocol step, posting messages. The `PostMessage`
command will need to be sent to this particular address that represents the
session that has been added to the chat room. The other aspect of a session is
that the client has revealed its own address, via the `replyTo` argument, so that subsequent
`MessagePosted` events can be sent to it.

This illustrates how Actors can express more than just the equivalent of method
calls on Java objects. The declared message types and their contents describe a
full protocol that can involve multiple Actors and that can evolve over
multiple steps. Here's the implementation of the chat room protocol:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #chatroom-behavior }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #chatroom-behavior }


The state is managed by changing behavior rather than using any variables.

When a new `GetSession` command comes in we add that client to the
list that is in the returned behavior. Then we also need to create the session’s
`ActorRef` that will be used to post messages. In this case we want to
create a very simple Actor that just repackages the `PostMessage`
command into a `PublishSessionMessage` command which also includes the
screen name.

The behavior that we declare here can handle both subtypes of `RoomCommand`.
`GetSession` has been explained already and the
`PublishSessionMessage` commands coming from the session Actors will
trigger the dissemination of the contained chat room message to all connected
clients. But we do not want to give the ability to send
`PublishSessionMessage` commands to arbitrary clients, we reserve that
right to the internal session actors we create—otherwise clients could pose as completely
different screen names (imagine the `GetSession` protocol to include
authentication information to further secure this). Therefore `PublishSessionMessage`
has `private` visibility and can't be created outside the `ChatRoom` @scala[object]@java[class].

If we did not care about securing the correspondence between a session and a
screen name then we could change the protocol such that `PostMessage` is
removed and all clients just get an @scala[`ActorRef[PublishSessionMessage]`]@java[`ActorRef<PublishSessionMessage>`] to
send to. In this case no session actor would be needed and we could just use
@scala[`ctx.self`]@java[`ctx.getSelf()`]. The type-checks work out in that case because
@scala[`ActorRef[-T]`]@java[`ActorRef<T>`] is contravariant in its type parameter, meaning that we
can use a @scala[`ActorRef[RoomCommand]`]@java[`ActorRef<RoomCommand>`] wherever an
@scala[`ActorRef[PublishSessionMessage]`]@java[`ActorRef<PublishSessionMessage>`] is needed—this makes sense because the
former simply speaks more languages than the latter. The opposite would be
problematic, so passing an @scala[`ActorRef[PublishSessionMessage]`]@java[`ActorRef<PublishSessionMessage>`] where
@scala[`ActorRef[RoomCommand]`]@java[`ActorRef<RoomCommand>`] is required will lead to a type error.

### Trying it out

In order to see this chat room in action we need to write a client Actor that can use it:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #chatroom-gabbler }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #chatroom-gabbler }

From this behavior we can create an Actor that will accept a chat room session,
post a message, wait to see it published, and then terminate. The last step
requires the ability to change behavior, we need to transition from the normal
running behavior into the terminated state. This is why here we do not return
`same`, as above, but another special value `stopped`.

@@@ div {.group-scala}

Since `SessionEvent` is a sealed trait the Scala compiler will warn us
if we forget to handle one of the subtypes; in this case it reminded us that
alternatively to `SessionGranted` we may also receive a
`SessionDenied` event.

@@@

Now to try things out we must start both a chat room and a gabbler and of
course we do this inside an Actor system. Since there can be only one guardian
supervisor we could either start the chat room from the gabbler (which we don’t
want—it complicates its logic) or the gabbler from the chat room (which is
nonsensical) or we start both of them from a third Actor—our only sensible
choice:

Scala
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/scala/docs/akka/typed/IntroSpec.scala) { #chatroom-main }

Java
:  @@snip [IntroSpec.scala]($akka$/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/IntroTest.java) { #chatroom-main }

In good tradition we call the `main` Actor what it is, it directly
corresponds to the `main` method in a traditional Java application. This
Actor will perform its job on its own accord, we do not need to send messages
from the outside, so we declare it to be of type @scala[`NotUsed`]@java[`Void`]. Actors receive not
only external messages, they also are notified of certain system events,
so-called Signals. In order to get access to those we choose to implement this
particular one using the `receive` behavior decorator. The
provided `onSignal` function will be invoked for signals (subclasses of `Signal`)
or the `onMessage` function for user messages.

This particular `main` Actor is created using `Behaviors.setup`, which is like a factory for a behavior.
Creation of the behavior instance is deferred until the actor is started, as opposed to `Behaviors.receive`
that creates the behavior instance immediately before the actor is running. The factory function in
`setup` is passed the `ActorContext` as parameter and that can for example be used for spawning child actors.
This `main` Actor creates the chat room and the gabbler and the session between them is initiated, and when the
gabbler is finished we will receive the `Terminated` event due to having
called `ctx.watch` for it. This allows us to shut down the Actor system: when
the main Actor terminates there is nothing more to do.

Therefore after creating the Actor system with the `main` Actor’s
`Behavior` we just await its termination.


## Relation to Akka (untyped) Actors

The most prominent difference is the removal of the `sender()` functionality.
The solution chosen in Akka Typed is to explicitly include the properly typed
reply-to address in the message, which both burdens the user with this task but
also places this aspect of protocol design where it belongs.

The other prominent difference is the removal of the `Actor` trait. In
order to avoid closing over unstable references from different execution
contexts (e.g. Future transformations) we turned all remaining methods that
were on this trait into messages: the behavior receives the
`ActorContext` as an argument during processing and the lifecycle hooks
have been converted into Signals.

A side-effect of this is that behaviors can now be tested in isolation without
having to be packaged into an Actor, tests can run fully synchronously without
having to worry about timeouts and spurious failures. Another side-effect is
that behaviors can nicely be composed and decorated, for example `Behaviors.tap`
is not special or using something internal. New combinators can be written as
external libraries or tailor-made for each project.

## A Little Bit of Theory

The [Actor Model](http://en.wikipedia.org/wiki/Actor_model) as defined by
Hewitt, Bishop and Steiger in 1973 is a computational model that expresses
exactly what it means for computation to be distributed. The processing
units—Actors—can only communicate by exchanging messages and upon reception of a
message an Actor can do the following three fundamental actions:

  1. send a finite number of messages to Actors it knows
  2. create a finite number of new Actors
  3. designate the behavior to be applied to the next message

The Akka Typed project expresses these actions using behaviors and addresses.
Messages can be sent to an address and behind this façade there is a behavior
that receives the message and acts upon it. The binding between address and
behavior can change over time as per the third point above, but that is not
visible on the outside.

With this preamble we can get to the unique property of this project, namely
that it introduces static type checking to Actor interactions: addresses are
parameterized and only messages that are of the specified type can be sent to
them. The association between an address and its type parameter must be made
when the address (and its Actor) is created. For this purpose each behavior is
also parameterized with the type of messages it is able to process. Since the
behavior can change behind the address façade, designating the next behavior is
a constrained operation: the successor must handle the same type of messages as
its predecessor. This is necessary in order to not invalidate the addresses
that refer to this Actor.

What this enables is that whenever a message is sent to an Actor we can
statically ensure that the type of the message is one that the Actor declares
to handle—we can avoid the mistake of sending completely pointless messages.
What we cannot statically ensure, though, is that the behavior behind the
address will be in a given state when our message is received. The fundamental
reason is that the association between address and behavior is a dynamic
runtime property, the compiler cannot know it while it translates the source
code.

This is the same as for normal Java objects with internal variables: when
compiling the program we cannot know what their value will be, and if the
result of a method call depends on those variables then the outcome is
uncertain to a degree—we can only be certain that the returned value is of a
given type.

We have seen above that the return type of an Actor command is described by the
type of reply-to address that is contained within the message. This allows a
conversation to be described in terms of its types: the reply will be of type
A, but it might also contain an address of type B, which then allows the other
Actor to continue the conversation by sending a message of type B to this new
address. While we cannot statically express the “current” state of an Actor, we
can express the current state of a protocol between two Actors, since that is
just given by the last message type that was received or sent.
