.. _typed-channels:

#############################
Typed Channels (EXPERIMENTAL)
#############################

.. note::

  *This is a preview of the upcoming Typed Channels support, its API may change
  during development up to the released version where the EXPERIMENTAL label is
  removed.*

Motivation
==========

Actors derive great strength from their strong encapsulation, which enables
internal restarts as well as changing behavior and also composition. The last
one is enabled by being able to inject an actor into a message exchange
transparently, because all either side ever sees is an :class:`ActorRef`. The
straight-forward way to implement this encapsulation is to keep the actor
references untyped, and before the advent of macros in Scala 2.10 this was the
only tractable way.

As a motivation for change consider the following simple example:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#motivation0

.. includecode:: code/docs/channels/ChannelDocSpec.scala#motivation1

This is an error which is quite common, and the reason is that the compiler
does not catch it and cannot warn about it. Now if there were some type
restrictions on which messages the ``commandProcessor`` can process, that would
be a different story:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#motivation2

The :class:`ChannelRef` wraps a normal untyped :class:`ActorRef`, but it
expresses a type constraint, namely that this channel accepts only messages of
type :class:`Request`, to which it may reply with messages of type
:class:`Reply`. The types do not express any guarantees on how many messages
will be exchanged, whether they will be received or processed, or whether a
reply will actually be sent. They only restrict those actions which are known
to be doomed already at compile time. In this case the second line would flag
an error, since the companion object ``Command`` is not an instance of type
:class:`Request`.

While this example looks pretty simple, the implications are profound. In order
to be useful, the system must be as reliable as you would expect a type system
to be. This means that unless you step outside of it (i.e. doing the
equivalent of ``.asInstanceOf[_]``) you shall be protected, failures shall be
recognized and flagged. There are a number of challenges included in this
requirement, which are discussed in `The Design Background`_ below.

Terminology
===========

.. describe:: type Channel[I, O] = (I, O)

  A Channel is a pair of an input type and and output type. The input type is
  the type of message accepted by the channel, the output type is the possible
  reply type and may be ``Nothing`` to signify that no reply is sent. The input
  type cannot be ``Nothing``.

.. describe:: type ChannelList

  A ChannelList is an ordered collection of Channels, without further
  restriction on the input or output types of these. This means that a single
  input type may be associated with multiple output types within the same
  ChannelList.

.. describe:: type TNil <: ChannelList

  The empty ChannelList.

.. describe:: type :+:[Channel, ChannelList] <: ChannelList

  This binary type constructor is used to build up lists of Channels, for which
  infix notation will be most convenient:

  .. includecode:: code/docs/channels/ChannelDocSpec.scala#motivation-types

.. describe:: class ChannelRef[T <: ChannelList]

  A ChannelRef is what is referred to above as the channel reference, it bears
  the ChannelList which describes all input and output types and their relation
  for the referenced actor. It also contains the underlying :class:`ActorRef`.

.. describe:: trait Channels[P <: ChannelList, C <: ChannelList]

  A mixin for the :class:`Actor` trait which is parameterized in the channel
  requirements this actor has for its parentChannel (P) and its selfChannel (C)
  (corresponding to ``context.parent`` and ``self`` for untyped Actors,
  respectively).

.. describe:: selfChannel

  An ``Actor with Channels[P, C]`` has a ``selfChannel`` of type
  ``ChannelRef[C]``. This is the same type of channel reference which is
  obtained by creating an instance of this actor.

.. describe:: parentChannel

  An ``Actor with Channels[P, C]`` has a ``parentChannel`` of type
  ``ChannelRef[P]``.

.. describe:: type ReplyChannels[T <: ChannelList] <: ChannelList

  Within an ``Actor with Channels[_, _]`` which takes a fully generic channel,
  i.e. a type argument ``T <: ChannelList`` which is part of its selfChannel
  type, this channel’s reply types are not known. The definition of this
  channel uses the ReplyChannels type to abstractly refer to this unknown set
  of channels in order to forward a reply from a ``ChannelRef[T]`` back to the
  original sender. This operation’s type-safety is ensured at the sender’s site
  by way of the ping-pong analysis described below.

.. describe:: class WrappedMessage[T <: ChannelList, LUB]

  Scala’s type system cannot directly express type unions. Asking an actor with
  a given input type may result in multiple possible reply types, hence the
  :class:`Future` holding this reply will contain the value wrapped inside a
  container which carries this type (only at compile-time). The type parameter
  LUB is the least upper bound of all input channels contained in the
  ChannelList T.

Sending Messages across Channels
================================

Sending messages is best demonstrated in a quick overview of the basic operations:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#sending

The first line is included so that the code compiles, since all message sends
including ``!`` will check the implicitly found selfChannel for compatibility
with the target channel’s reply types. In this case we want to demonstrate just
the syntax of sending, hence the dummy sender which accepts everything and
replies never.

Presupposing three channel references of chainable types (and a fourth one for
demonstrating multiple reply type), an input value ``a`` and a Future holding
such a value, we demonstrate the two basic operations which are well known from
untyped actors: tell/! and ask/?. The type of the Future returned by the ask
operation on ``channelA2`` may seem surprising at first, but keeping track of
all possible reply types is necessary to enable sending of replies to other
actors which do support all possibilities. This is especially handy in
situations like the one demonstrated on the last line.  What the last line does
is the following:

* it asks channelA, which returns a Future

* a callback is installed on the Future which will use the reply value of
  channelA and ask channelB with it, returning another Future

* a callback is installed on that Future to send the reply value of channelB to
  channelC, returning a Future with that previously sent value (using ``andThen``)

This example also motivates the introduction of the “turned-around” syntax
where messages flow more naturally from left to right, instead of the standard
object-oriented view of having the tell method operate on the ActorRef given to
the left.

This example informally introduced what is more precisely specified in the
following subsection.

The Rules
---------

Operations on typed channels are composable and obey a few simple rules:

* the message to be sent can be one of three things:

  * a :class:`Future[_]`, in which case the contained value will be sent once
    available; the value will be unwrapped if it is a :class:`WrappedMessage[_, _]`

  * a :class:`WrappedMessage[_, _]`, which will be unwrapped (i.e. only the
    value is sent)

  * everything else is sent as is

* the operators are fully symmetric, i.e. ``-!->`` and ``<-!-`` do the same
  thing provided the arguments also switch places

* sending with ``-?->`` or ``<-?-`` returns a ``Future[WrappedMessage[_, _]]``
  representing all possible reply channels if there is more than one (use
  ``.lub`` to get a :class:`Future[_]` with the most precise single type for
  the value)

* sending a :class:`Future[_]` with ``-!->`` or ``<-!-`` returns a new
  :class:`Future[_]` which will be completed with the value after it has been
  sent; sending a strict value returns that value

Declaring an Actor with Channels
================================

The declaration of an Actor with Channels is done like this:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#declaring-channels

It should be noted that it is impossible to declare channels which are not part
of the channel list given as the second type argument to the :class:`Channels`
trait. It is also checked—albeit at runtime—that when the actor’s construction
is complete (i.e. its constructor and ``preStart`` hook have run) every channel
listed in the selfChannel type parameter has been declared. This can in general
not be done at compile time, both due to the possibility of overriding
subclasses as well as the problem that the compiler cannot determine whether a
``channel[]`` statement will be called in the course of execution due to
external inputs (e.g. if conditionally executed).

It should also be noted that the type of ``req`` in this example is
``Request``, hence it would be a compile-time error to try to match against the
``Command`` companion object. The ``snd`` reference is the sender channel
reference, which in this example is of type
``ChannelRef[(Reply, UnknownDoNotWriteMeDown) :+: TNil]``, meaning that sending
back a reply which is not of type ``Reply`` would be a compile-time error.

The last thing to note is that an actor is not obliged to reply to an incoming
message, even if that was successfully delivered to it: it might not be
appropriate, or it might be impossible, the actor might have failed before
executing the replying message send, etc. And as always, the ``snd`` reference
may be used more than once, and even stored away for later. It must not leave
the actor within it was created, however, because that would defeat the
ping-pong check; this is the reason for the curious name of the fabricated
reply type ``UnknownDoNotWriteMeDown``; if you find yourself declaring that
type as part of a message or similar you know that you are cheating.

Declaration of Subchannels
--------------------------

It can be convenient to carve out subchannels for special treatment like so:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#declaring-subchannels

This means that all ``Command`` requests will be positively answered while all
others may or may not be lucky. This dispatching between the two declarations
does not depend on their order but is solely done based on which type is more
specific—but see the restrictions imposed by JVM type erasure below.

Forwarding Messages
-------------------

Forwarding messages has been hinted at in the last sample already, but here is
a more complete sample actor:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#forwarding
   :exclude: become

This actor declares a single-Channel parametric type which it forwards to a
target actor, handing replies back to the original sender using the ask/pipe
pattern.

.. note::

  It is important not to forget the ``TypeTag`` context bound for all type
  arguments which are used in channel declarations, otherwise the not very
  helpful error “Predef is not an enclosing class” will haunt you.

Changing Behavior at Runtime
----------------------------

The actor from the previous example gets a lot more interesting when
implementing its control channel:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#forwarding

This shows all elements of the toolkit in action: calling ``channel[T1]`` again
during the lifetime of the actor will alter its behavior on that channel. In
this case a latch or gate is modeled which when closed will permit the messages
to flow through and when not will drop the messages to the floor.

Creating Actors with Channels
-----------------------------

Creating top-level actors with channels is done using the ``ChannelExt`` extension:

.. includecode:: code/docs/channels/ChannelDocSpec.scala
   :include: usage
   :exclude: processing

Inside an actor with channels children are created using the ``createChild`` method:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#child

In this example we create a simple child actor which responds to requests, but
also keeps its parent informed about what it is doing. The parent channel
within the child is thus declared to accept :class:`Stats` messages, and the
parent must consequently declare such a channel in order to be able to create
such a child. The parent’s job then is to create the child, make it available
to the outside via properly typed messages and collect the statistics coming in
from the child.

Stepping Outside of Type-Safety
-------------------------------

In much the same was as Scala’s type system can be circumvented by using
``.asInstanceOf[_]`` typed channels can also be circumvented. Casting them to
alter the type arguments would be an obvious way of doing that, but there are
less obvious ways which are therefore enumerated here:

* explicitly constructing :class:`ChannelRef` instances by hand allows using
  arbitrary types as arguments

* sending to the ``actorRef`` member of the :class:`ChannelRef`; this is a
  normal untyped actor reference without any compile-time checks, which is the
  reason for choosing visibly different operator names for typed and untyped
  message send operations

* using the ``context.parent`` reference instead of ``parentChannel``

* using the untyped ``sender`` reference instead of the second argument to a
  channel’s behavior function

Sending unforeseen messages will be flagged as a type error as long as none of
these techniques are used within an application.

Implementation Restrictions
---------------------------

As described below, incoming messages are dispatched to declared channels based
on their runtime class information.  This erasure-based dispatch of messages
requires all declared channels to have unique JVM type representations, i.e. it
is not possible to have two channel declarations with types ``List[A]`` and
``List[B]`` because both would at runtime only be known as ``List[_]``.

The specific dispatch mechanism also requires the declaration of all channels or
subchannels during the actor’s construction, independent of whether they shall
later change behavior or not. Changing behavior for a subchannel is only
possible if that subchannel was declared up-front.

TypeTags are currently (Scala 2.10.0) not serializable, hence narrowing of
:class:`ActorRef` does not work for remote references.

The Design Background
=====================

This section outlines the most prominent challenges encountered during the
development of Typed Channels and the rationale for their solutions. It is not
necessary to understand this material in order to use Typed Channels, but it
may be useful to explain why certain things are as they are.

The Type Pollution Problem
--------------------------

What if an actor accepts two different types of messages? It might be a main
communications channel which is forwarded to worker actors for performing some
long-running and/or dangerous task, plus an administrative channel for the
routing of requests. Or it might be a generic message throttler which accepts a
generic channel for passing it through (which delay where appropriate) and a
management channel for setting the throttling rate. In the second case it is
especially easy to see that those two channels will probably not be related,
their types will not be derived from a meaningful common supertype; instead the
least upper bound will probably be :class:`AnyRef`. If a typed channel
reference only had the capability to express a single type, this type would
then be no restriction anymore. This loss of type safety caused by the need of
handling multiple disjoint sets of types is called “type pollution”, the term
was coined by Prof. Philip Wadler.

One solution to this is to never expose references describing more than one
channel at a time. But where would these references come from? It would be very
difficult to make this construction process type-safe, and it would also be an
inconvenient restriction, since message ordering guarantees only apply for the
same sender–receive pair: if there are relations between the messages sent
on multiple channels then implementing this mixed-channel communication would
incur programmatic and runtime overhead compared to just sending to the same
untyped reference.

The other solution thus is to express multiple channel types by a single
channel reference, which requires the implementation of type lists and
computations on these. And as we will see below it also requires the
specification of possibly multiple reply channels per input type, hence a type
map. The implementation chosen uses type lists like this:

.. includecode:: code/docs/channels/ChannelDocSpec.scala#motivation-types

This type expresses two channels: type ``A`` may stimulate replies of type
``B``, while type ``C`` may evoke replies of type ``D``. The type operator
``:+:`` is a binary type constructor which forms a list of these channel
definitions, and like every good list it ends with an empty tail ``TNil``.

The Reply Problem
-----------------

Akka actors have the power to reply to any message they receive, which is also
a message send and shall also be covered by typed channels. Since the sending
actor is the one which will also receive the reply, this needs to be verified.
The solution to this problem is that in addition to the ``self`` reference,
which is implicitly picked up as the sender for untyped actor interactions,
there is also a ``selfChannel`` which describes the typed channels handled by
this actor. Thus at the call site of the message send it must be verified that
this actor can actually handle the reply for that given message send.

The Sender Ping-Pong Problem
----------------------------

After successfully sending a message to an actor over a typed channel, that
actor will have a reference to the message’s sender, because normal Akka
message processing rules apply. For this sender reference there must exist a
typed channel reference which describes the possible reply types which are
applicable for each of the incoming message channels. We will see below how
this reference is provided in the code, the problem we want to highlight here
is a different one: the nature of any sender reference is that it is highly
dynamic, the compiler cannot possibly know who sent the message we are
currently processing.

But this does not mean that all hope is lost: the solution is to do *all*
type-checking at the call site of the message send. The receiving actor just
needs to declare its channel descriptions in its own type, and channel
references are derived at construction from this type (implying the existence
of a typed ``actorOf``). Then the actor knows for each received message type
which the allowed reply types are. The typed channel for the sender reference
hence has the reply types for the current input channel as its own input types,
but what should the reply types be? This is the ping-pong problem:

* ActorA sends MsgA to ActorB

* ActorB replies with MsgB

* ActorA replies with MsgC

Every “reply” uses the sender channel, which is dynamic and hence only known
partially. But ActorB did not know who sent the message it just replied to and
hence it cannot check that it can process the possible replies following that
message send. Only ActorA could have known, because it knows its own channels
as well as ActorB’s channels completely. The solution is thus to recursively
verify the message send, following all reply channels until all possible
message types to be sent have been verified. This sounds horribly complex, but
the algorithm for doing so actually has a worst-case complexity of O(N) where N
is the number of input channels of ActorA or ActorB, whoever has fewer.

The Parent Problem
------------------

There is one other actor reference which is available to every actor: its
parent. Since the child–parent relationship is established permanently when the
child is created by the parent, this problem is easily solvable by encoding the
requirements of the child for its parent channel in its type signature and
having the typed variant of ``actorOf`` verify this against the
``selfChannel``.

Anecdotally, since the guardian actor does not care at all about messages sent
to it, top-level actors with typed channels must declare their parent channel
to be empty.

The Exposure/Restriction Problem
--------------------------------

An actor may provide more than one service, either itself or by proxy, each
with their own set of channels. Only having references for the full set of
channels leads to a too wide spread of capabilities: in the example of the
message rate throttling actor its management channel is only meant to be used
by the actor which inserted it, not by the two actors between it was inserted.
Hence the manager will have to create a channel reference which excludes the
management channels before handing out the reference to other actors.

Another variant of this problem is an actor which handles a channel whose input
type is a supertype for a number of derived channels. It should be allowed to
use the “superchannel” in place of any of the subchannels, but not the other
way around. The intuitive approach would be to model this by making the channel
reference contravariant in its channel types and define those channel types
accordingly. This does not work nicely, however, because Scala’s type system is
not well-suited to modeling such calculations on unordered type lists; it might
be possible but its implementation would be forbiddingly complex.

Therefore this topic gained traction as macros became available: being able to
write down type calculations using standard collections and their
transformations reduces the implementation to a handful of lines. The “narrow”
operation implemented this way allows narrowing of input channels and
widening of output channels down to ``(Nothing, Any)`` (which is to say that
channels may be narrowed or just plain removed from a channel list).

The Forwarding Problem
----------------------

One important feature of actors mentioned above is their composability which is
enabled by being able to forward or delegate messages. It is the nature of this
process that the sending party is not aware of the true destination of the
message, it only sees the façade in front of it. Above we have seen that the
sender ping-pong problem requires all verification to be performed at the
sender’s end, but if the sender does not know the final recipient, how can it
check that the message exchange is type-safe?

The forwarding party—the middle-man—is also not in the position to make this
call, since all it has is the incomplete sender channel which is lacking reply
type information. The problem which arises lies precisely in these reply
sequences: the ping-pong scheme was verified against the middle-man, and if the
final recipient would reply to the forwarded request, that sender reference
would belong to a different channel and there is no single location in the
source code where all these pieces are known at compile time.

The solution to this problem is to not allow forwarding in the normal untyped
:class:`ActorRef` sense. Replies must always be sent by the recipient of the
original message in order for the type checks at the sender site to be
effective. Since forwarding is an important communication pattern among actors,
support for it is thus provided in the form of the :meth:`ask` pattern combined
with the :meth:`pipe` pattern, which both are not add-ons but fully integrated
operations among typed channels.

The JVM Erasure Problem
-----------------------

When an actor with typed channels receives a message, this message needs to be
dispatched internally to the right channel, so that the right sender channel
can be presented and so on. This dispatch needs to work with the information
contained in the message, which due to the erasure of generic type information
is an incomplete image of the true channel types. Those full types exist only
at compile-time and reifying them into TypeTags at runtime for every message
send would be prohibitively expensive. This means that channels which erase to
the same JVM type cannot coexist within the same actor, messages would not be
routable reliably in that case.

The Actor Lookup Problem
------------------------

Everything up to this point has assumed that channel references are passed from
their point of creation to their point of use directly and in the regime of
strong, unerased types. This can also happen between actors by embedding them
in case classes with proper type information. But one particular useful feature
of Akka actors is that they have a stable identity by which they can be found,
a unique name. This name is represented as a :class:`String` and naturally does
not bear any type information concerning the actor’s channels. Thus, when
looking up an actor with ``system.actorSelection(...)`` followed by an ``Identify``
request you will only get an untyped :class:`ActorRef` and not a channel reference.
This :class:`ActorRef` can of course manually be wrapped in a channel reference
bearing the desired channels, but this is not a type-safe operation.

The solution in this case must be a runtime check. There is an operation to
“narrow” an :class:`ActorRef` to a channel reference of given type, which
behind the scenes will send a message to the designated actor with a TypeTag
representing the requested channels. The actor will check these against its own
TypeTag and reply with the verification result. This check uses the same code
as the compile-time “narrow” operation introduced above.

How to read The Types
=====================

In case of errors in your code the compiler will try to inform you in the most
precise way it can, and that will then contain types like this::

  akka.channels.:+:[(com.example.Request, com.example.Reply),
    akka.channels.:+:[(com.example.Command, Nothing), TNil]]

These types look unwieldy because of two things: they use fully qualified names
for all the types (thankfully using the ``()`` sugar for :class:`Tuple2`), and
they do not employ infix notation. That same type there might look like this in
your source code::

  (Request, Reply) :+: (Command, Nothing) :+: TNil

As soon as someone finds the time, it would be nice if the IDEs learned to
print types making use of the file’s import statements and infix notation.
