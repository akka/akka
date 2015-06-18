.. _typed-scala:

##########
Akka Typed
##########

.. warning::

  This module is currently experimental in the sense of being the subject of
  active research. This means that API or semantics can change without warning
  or deprecation period and it is not recommended to use this module in
  production just yet—you have been warned.

As discussed in :ref:`actor-systems` (and following chapters) Actors are about
sending messages between independent units of computation, but how does that
look like? In all of the following these imports are assumed:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#imports

With these in place we can define our first Actor, and of course it will say
hello!

.. includecode:: code/docs/akka/typed/IntroSpec.scala#hello-world-actor

This small piece of code defines two message types, one for commanding the
Actor to greet someone and one that the Actor will use to confirm that it has
done so. The :class:`Greet` type contains not only the information of whom to
greet, it also holds an :class:`ActorRef` that the sender of the message
supplies so that the :class:`HelloWorld` Actor can send back the confirmation
message.

The behavior of the Actor is defined as the :meth:`greeter` value with the help
of the :class:`Static` behavior constructor—there are many different ways of
formulating behaviors as we shall see in the following. The “static” behavior
is not capable of changing in response to a message, it will stay the same
until the Actor is stopped by its parent.

The type of the messages handled by this behavior is declared to be of class
:class:`Greet`, which implies that the supplied function’s ``msg`` argument is
also typed as such. This is why we can access the ``whom`` and ``replyTo``
members without needing to use a pattern match.

On the last line we see the :class:`HelloWorld` Actor send a message to another
Actor, which is done using the ``!`` operator (pronounced “tell”). Since the
``replyTo`` address is declared to be of type ``ActorRef[Greeted]`` the
compiler will only permit us to send messages of this type, other usage will
not be accepted.

The accepted message types of an Actor together with all reply types defines
the protocol spoken by this Actor; in this case it is a simple request–reply
protocol but Actors can model arbitrarily complex protocols when needed. The
protocol is bundled together with the behavior that implements it in a nicely
wrapped scope—the ``HelloWorld`` object.

Now we want to try out this Actor, so we must start an Actor system to host it:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#hello-world

After importing the Actor’s protocol definition we start an Actor system from
the defined behavior, wrapping it in :class:`Props` like an actor on stage. The
props we are giving to this one are just the defaults, we could at this point
also configure how and where the Actor should be deployed in a clustered
system.

As Carl Hewitt said, one Actor is no Actor—it would be quite lonely with
nobody to talk to. In this sense the example is a little cruel because we only
give the ``HelloWorld`` Actor a fake person to talk to—the “ask” pattern
(represented by the ``?`` operator) can be used to send a message such that the
reply fulfills a Promise to which we get back the corresponding Future.

Note that the :class:`Future` that is returned by the “ask” operation is
properly typed already, no type checks or casts needed. This is possible due to
the type information that is part of the message protocol: the ``?`` operator
takes as argument a function that accepts an :class:`ActorRef[U]` (which
explains the ``_`` hole in the expression on line 6 above) and the ``replyTo``
parameter which we fill in like that is of type ``ActorRef[Greeted]``, which
means that the value that fulfills the :class:`Promise` can only be of type
:class:`Greeted`.

We use this here to send the :class:`Greet` command to the Actor and when the
reply comes back we will print it out and tell the actor system to shut down.
Once that is done as well we print the ``"system terminated"`` messages and the
program ends. The ``recovery`` combinator on the original :class:`Future` is
needed in order to ensure proper system shutdown even in case something went
wrong; the ``flatMap`` and ``map`` combinators that the ``for`` expression gets
turned into care only about the “happy path” and if the ``future`` failed with
a timeout then no ``greeting`` would be extracted and nothing would happen.

This shows that there are aspects of Actor messaging that can be type-checked
by the compiler, but this ability is not unlimited, there are bounds to what we
can statically express. Before we go on with a more complex (and realistic)
example we make a small detour to highlight some of the theory behind this.

A Little Bit of Theory
======================

The `Actor Model`_ as defined by Hewitt, Bishop and Steiger in 1973 is a
computational model that expresses exactly what it means for computation to be
distributed. The processing units—Actors—can only communicate by exchanging
messages and upon reception of a message an Actor can do the following three
fundamental actions:

.. _`Actor Model`: http://en.wikipedia.org/wiki/Actor_model

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

In the next section we demonstrate this on a more realistic example.

A More Complex Example
======================

Consider an Actor that runs a chat room: client Actors may connect by sending
a message that contains their screen name and then they can post messages. The
chat room Actor will disseminate all posted messages to all currently connected
client Actors. The protocol definition could look like the following:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#chatroom-protocol

Initially the client Actors only get access to an ``ActorRef[GetSession]``
which allows them to make the first step. Once a client’s session has been
established it gets a :class:`SessionGranted` message that contains a ``handle`` to
unlock the next protocol step, posting messages. The :class:`PostMessage`
command will need to be sent to this particular address that represents the
session that has been added to the chat room. The other aspect of a session is
that the client has revealed its own address, via the ``replyTo`` argument, so that subsequent
:class:`MessagePosted` events can be sent to it.

This illustrates how Actors can express more than just the equivalent of method
calls on Java objects. The declared message types and their contents describe a
full protocol that can involve multiple Actors and that can evolve over
multiple steps. The implementation of the chat room protocol would be as simple
as the following:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#chatroom-behavior

The core of this behavior is again static, the chat room itself does not change
into something else when sessions are established, but we introduce a variable
that tracks the opened sessions. When a new :class:`GetSession` command comes
in we add that client to the list and then we need to create the session’s
:class:`ActorRef` that will be used to post messages. In this case we want to
create a very simple Actor that just repackages the :class:`PostMessage`
command into a :class:`PostSessionMessage` command which also includes the
screen name. Such a wrapper Actor can be created by using the
:meth:`spawnAdapter` method on the :class:`ActorContext`, so that we can then
go on to reply to the client with the :class:`SessionGranted` result.

The behavior that we declare here can handle both subtypes of :class:`Command`.
:class:`GetSession` has been explained already and the
:class:`PostSessionMessage` commands coming from the wrapper Actors will
trigger the dissemination of the contained chat room message to all connected
clients. But we do not want to give the ability to send
:class:`PostSessionMessage` commands to arbitrary clients, we reserve that
right to the wrappers we create—otherwise clients could pose as complete
different screen names (imagine the :class:`GetSession` protocol to include
authentication information to further secure this). Therefore we narrow the
behavior down to only accepting :class:`GetSession` commands before exposing it
to the world, hence the type of the ``behavior`` value is
:class:`Behavior[GetSession]` instead of :class:`Behavior[Command]`.

Narrowing the type of a behavior is always a safe operation since it only
restricts what clients can do. If we were to widen the type then clients could
send other messages that were not foreseen while writing the source code for
the behavior.

If we did not care about securing the correspondence between a session and a
screen name then we could change the protocol such that :class:`PostMessage` is
removed and all clients just get an :class:`ActorRef[PostSessionMessage]` to
send to. In this case no wrapper would be needed and we could just use
``ctx.self``. The type-checks work out in that case because
:class:`ActorRef[-T]` is contravariant in its type parameter, meaning that we
can use a :class:`ActorRef[Command]` wherever an
:class:`ActorRef[PostSessionMessage]` is needed—this makes sense because the
former simply speaks more languages than the latter. The opposite would be
problematic, so passing an :class:`ActorRef[PostSessionMessage]` where
:class:`ActorRef[Command]` is required will lead to a type error.

The final piece of this behavior definition is the :class:`ContextAware`
decorator that we use in order to obtain access to the :class:`ActorContext`
within the :class:`Static` behavior definition. This decorator invokes the
provided function when the first message is received and thereby creates the
real behavior that will be used going forward—the decorator is discarded after
it has done its job.

Trying it out
-------------

In order to see this chat room in action we need to write a client Actor that can use it:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#chatroom-gabbler

From this behavior we can create an Actor that will accept a chat room session,
post a message, wait to see it published, and then terminate. The last step
requires the ability to change behavior, we need to transition from the normal
running behavior into the terminated state. This is why this Actor uses a
different behavior constructor named :class:`Total`. This constructor takes as
argument a function from the handled message type, in this case
:class:`SessionEvent`, to the next behavior. That next behavior must again be
of the same type as we discussed in the theory section above. Here we either
stay in the very same behavior or we terminate, and both of these cases are so
common that there are special values ``Same`` and ``Stopped`` that can be used.
The behavior is named “total” (as opposed to “partial”) because the declared
function must handle all values of its input type. Since :class:`SessionEvent`
is a sealed trait the Scala compiler will warn us if we forget to handle one of
the subtypes; in this case it reminded us that alternatively to
:class:`SessionGranted` we may also receive a :class:`SessionDenied` event.

Now to try things out we must start both a chat room and a gabbler and of
course we do this inside an Actor system. Since there can be only one guardian
supervisor we could either start the chat room from the gabbler (which we don’t
want—it complicates its logic) or the gabbler from the chat room (which is
nonsensical) or we start both of them from a third Actor—our only sensible
choice:

.. includecode:: code/docs/akka/typed/IntroSpec.scala#chatroom-main

In good tradition we call the ``main`` Actor what it is, it directly
corresponds to the ``main`` method in a traditional Java application. This
Actor will perform its job on its own accord, we do not need to send messages
from the outside, so we declare it to be of type ``Unit``. Actors receive not
only external messages, they also are notified of certain system events,
so-called Signals. In order to get access to those we choose to implement this
particular one using the :class:`Full` behavior decorator. The name stems from
the fact that within this we have full access to all aspects of the Actor. The
provided function will be invoked for signals (wrapped in :class:`Sig`) or user
messages (wrapped in :class:`Msg`) and the wrapper also contains a reference to
the :class:`ActorContext`.

This particular main Actor reacts to two signals: when it is started it will
first receive the :class:`PreStart` signal, upon which the chat room and the
gabbler are created and the session between them is initiated, and when the
gabbler is finished we will receive the :class:`Terminated` event due to having
called ``ctx.watch`` for it. This allows us to shut down the Actor system: when
the main Actor terminates there is nothing more to do.

Therefore after creating the Actor system with the ``main`` Actor’s
:class:`Props` we just await its termination.

Status of this Project and Relation to Akka Actors
==================================================

Akka Typed is the result of many years of research and previous attempts
(including Typed Channels in the 2.2.x series) and it is on its way to
stabilization, but maturing such a profound change to the core concept of Akka
will take a long time. We expect that this module will stay experimental for
multiple major releases of Akka and the plain ``akka.actor.Actor`` will not be
deprecated or go away anytime soon.

Being a research project also entails that the reference documentation is not
as detailed as it will be for a final version, please refer to the API
documentation for greater depth and finer detail.

Main Differences
----------------

The most prominent difference is the removal of the ``sender()`` functionality.
This turned out to be the Achilles heel of the Typed Channels project, it is
the feature that makes its type signatures and macros too complex to be viable.
The solution chosen in Akka Typed is to explicitly include the properly typed
reply-to address in the message, which both burdens the user with this task but
also places this aspect of protocol design where it belongs.

The other prominent difference is the removal of the :class:`Actor` trait. In
order to avoid closing over unstable references from different execution
contexts (e.g. Future transformations) we turned all remaining methods that
were on this trait into messages: the behavior receives the
:class:`ActorContext` as an argument during processing and the lifecycle hooks
have been converted into Signals.

A side-effect of this is that behaviors can now be tested in isolation without
having to be packaged into an Actor, tests can run fully synchronously without
having to worry about timeouts and spurious failures. Another side-effect is
that behaviors can nicely be composed and decorated, see the :class:`And`,
:class:`Or`, :class:`Widened`, :class:`ContextAware` combinators; nothing about
these is special or internal, new combinators can be written as external
libraries or tailor-made for each project.
