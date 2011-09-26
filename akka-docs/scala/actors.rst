.. _actors-scala:

Actors (Scala)
==============

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

The `Actor Model <http://en.wikipedia.org/wiki/Actor_model>`_ provides a higher level of abstraction for writing concurrent and distributed systems. It alleviates the developer from having to deal with explicit locking and thread management, making it easier to write correct concurrent and parallel systems. Actors were defined in the 1973 paper by Carl Hewitt but have been popularized by the Erlang language, and used for example at Ericsson with great success to build highly concurrent and reliable telecom systems.

The API of Akka’s Actors is similar to Scala Actors which has borrowed some of its syntax from Erlang.

The Akka 0.9 release introduced a new concept; ActorRef, which requires some refactoring. If you are new to Akka just read along, but if you have used Akka 0.6.x, 0.7.x and 0.8.x then you might be helped by the :doc:`0.8.x => 0.9.x migration guide </project/migration-guide-0.8.x-0.9.x>`

Creating Actors
---------------

Actors can be created either by:

* Extending the Actor class and implementing the receive method.
* Create an anonymous actor using one of the actor methods.

Defining an Actor class
^^^^^^^^^^^^^^^^^^^^^^^

Actor classes are implemented by extending the Actor class and implementing the ``receive`` method. The ``receive`` method should define a series of case statements (which has the type ``PartialFunction[Any, Unit]``) that defines which messages your Actor can handle, using standard Scala pattern matching, along with the implementation of how the messages should be processed.

Here is an example:

.. code-block:: scala

  import akka.actor.Actor
  import akka.event.EventHandler
  
  class MyActor extends Actor {
    def receive = {
      case "test" => EventHandler.info(this, "received test")
      case _ => EventHandler.info(this, "received unknown message")
    }
  }

Please note that the Akka Actor ``receive`` message loop is exhaustive, which is different compared to Erlang and Scala Actors. This means that you need to provide a pattern match for all messages that it can accept and if you want to be able to handle unknown messages then you need to have a default case as in the example above.

Creating Actors
^^^^^^^^^^^^^^^

.. code-block:: scala

  val myActor = Actor.actorOf[MyActor]

Normally you would want to import the ``actorOf`` method like this:

.. code-block:: scala

  import akka.actor.Actor._

  val myActor = actorOf[MyActor]

To avoid prefixing it with ``Actor`` every time you use it.

The call to ``actorOf`` returns an instance of ``ActorRef``. This is a handle to the ``Actor`` instance which you can use to interact with the ``Actor``. The ``ActorRef`` is immutable and has a one to one relationship with the Actor it represents. The ``ActorRef`` is also serializable and network-aware. This means that you can serialize it, send it over the wire and use it on a remote host and it will still be representing the same Actor on the original node, across the network.

Creating Actors with non-default constructor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If your Actor has a constructor that takes parameters then you can't create it using ``actorOf[TYPE]``. Instead you can use a variant of ``actorOf`` that takes a call-by-name block in which you can create the Actor in any way you like.

Here is an example:

.. code-block:: scala

  val a = actorOf(new MyActor(..)) // allows passing in arguments into the MyActor constructor

Running a block of code asynchronously
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Here we create a light-weight actor-based thread, that can be used to spawn off a task. Code blocks spawned up like this are always implicitly started, shut down and made eligible for garbage collection. The actor that is created "under the hood" is not reachable from the outside and there is no way of sending messages to it. It being an actor is only an implementation detail. It will only run the block in an event-based thread and exit once the block has run to completion.

.. code-block:: scala

  spawn {
    ... // do stuff
  }

Actor Internal API
------------------

The :class:`Actor` trait defines only one abstract method, the abovementioned
:meth:`receive`. In addition, it offers two convenience methods
:meth:`become`/:meth:`unbecome` for modifying the hotswap behavior stack as
described in :ref:`Actor.HotSwap` and the :obj:`self` reference to this actor’s
:class:`ActorRef` object. If the current actor behavior does not match a
received message, :meth:`unhandled` is called, which by default throws an
:class:`UnhandledMessageException`.

The remaining visible methods are user-overridable life-cycle hooks which are
described in the following::

  def preStart() {}
  def preRestart(cause: Throwable, message: Option[Any]) {}
  def postRestart(cause: Throwable) {}
  def postStop() {}

The implementations shown above are the defaults provided by the :class:`Actor`
trait.

Start Hook
^^^^^^^^^^

Right after starting the actor, its :meth:`preStart` method is invoked. This is
guaranteed to happen before the first message from external sources is queued
to the actor’s mailbox.

::

  override def preStart {
    // e.g. send initial message to self
    self ! GetMeStarted
    // or do any other stuff, e.g. registering with other actors
    someService ! Register(self)
  }

Restart Hooks
^^^^^^^^^^^^^

A supervised actor, i.e. one which is linked to another actor with a fault
handling strategy, will be restarted in case an exception is thrown while
processing a message. This restart involves four of the hooks mentioned above:

1. The old actor is informed by calling :meth:`preRestart` with the exception
   which caused the restart and the message which triggered that exception; the
   latter may be ``None`` if the restart was not caused by processing a
   message, e.g. when a supervisor does not trap the exception and is restarted
   in turn by its supervisor. This method is the best place for cleaning up,
   preparing hand-over to the fresh actor instance, etc.
2. The initial factory from the ``Actor.actorOf`` call is used
   to produce the fresh instance.
3. The new actor’s :meth:`preStart` method is invoked, just as in the normal
   start-up case.
4. The new actor’s :meth:`postRestart` method is called with the exception
   which caused the restart.


An actor restart replaces only the actual actor object; the contents of the
mailbox and the hotswap stack are unaffected by the restart, so processing of
messages will resume after the :meth:`postRestart` hook returns. Any message
sent to an actor while it is being restarted will be queued to its mailbox as
usual.
 
Stop Hook
^^^^^^^^^

After stopping an actor, its :meth:`postStop` hook is called, which may be used
e.g. for deregistering this actor from other services. This hook is guaranteed
to run after message queuing has been disabled for this actor, i.e. sending
messages would fail with an :class:`IllegalActorStateException`.

Identifying Actors
------------------

Each Actor has two fields:

* ``self.uuid``
* ``self.id``

The difference is that the ``uuid`` is generated by the runtime, guaranteed to be unique and can't be modified. While the ``id`` is modifiable by the user, and defaults to the Actor class name. You can retrieve Actors by both UUID and ID using the ``ActorRegistry``, see the section further down for details.

Messages and immutability
-------------------------

**IMPORTANT**: Messages can be any kind of object but have to be immutable. Scala can’t enforce immutability (yet) so this has to be by convention. Primitives like String, Int, Boolean are always immutable. Apart from these the recommended approach is to use Scala case classes which are immutable (if you don’t explicitly expose the state) and works great with pattern matching at the receiver side.

Here is an example:

.. code-block:: scala

  // define the case class
  case class Register(user: User)

  // create a new case class message
  val message = Register(user)

Other good messages types are ``scala.Tuple2``, ``scala.List``, ``scala.Map`` which are all immutable and great for pattern matching.

Send messages
-------------

Messages are sent to an Actor through one of the following methods.

* ``!`` means “fire-and-forget”, e.g. send a message asynchronously and return
  immediately.
* ``?`` sends a message asynchronously and returns a :class:`Future`
  representing a possible reply.

.. note::

  There used to be two more “bang” methods, which are deprecated and will be
  removed in Akka 2.0:

  * ``!!`` was similar to the current ``(actor ? msg).as[T]``; deprecation
    followed from the change of timeout handling described below.
  * ``!!![T]`` was similar to the current ``(actor ? msg).mapTo[T]``, with the
    same change in the handling of :class:`Future`’s timeout as for ``!!``, but
    additionally the old method could defer possible type cast problems into
    seemingly unrelated parts of the code base.

Fire-forget
^^^^^^^^^^^

This is the preferred way of sending messages. No blocking waiting for a
message. This gives the best concurrency and scalability characteristics.

.. code-block:: scala

  actor ! "Hello"

If invoked from within an Actor, then the sending actor reference will be
implicitly passed along with the message and available to the receiving Actor
in its ``channel: UntypedChannel`` member field. The target actor can use this
to reply to the original sender, e.g. by using the ``self.reply(message: Any)``
method.

If invoked from an instance that is **not** an Actor there will be no implicit
sender passed along with the message and you will get an
IllegalActorStateException when calling ``self.reply(...)``.

Send-And-Receive-Future
^^^^^^^^^^^^^^^^^^^^^^^

Using ``?`` will send a message to the receiving Actor asynchronously and
will return a :class:`Future`:

.. code-block:: scala

  val future = actor ? "Hello"

The receiving actor should reply to this message, which will complete the
future with the reply message as value; if the actor throws an exception while
processing the invocation, this exception will also complete the future. If the
actor does not complete the future, it will expire after the timeout period,
which is taken from one of the following three locations in order of
precedence:

#. explicitly given timeout as in ``actor.?("hello")(timeout = 12 millis)``
#. implicit argument of type :class:`Actor.Timeout`, e.g.

   ::

     implicit val timeout = Actor.Timeout(12 millis)
     val future = actor ? "hello"

#. default timeout from ``akka.conf``

See :ref:`futures-scala` for more information on how to await or query a
future.

Send-And-Receive-Eventually
^^^^^^^^^^^^^^^^^^^^^^^^^^^

The future returned from the ``?`` method can conveniently be passed around or
chained with further processing steps, but sometimes you just need the value,
even if that entails waiting for it (but keep in mind that waiting inside an
actor is prone to dead-locks, e.g. if obtaining the result depends on
processing another message on this actor).

For this purpose, there is the method :meth:`Future.as[T]` which waits until
either the future is completed or its timeout expires, whichever comes first.
The result is then inspected and returned as :class:`Some[T]` if it was
normally completed and the answer’s runtime type matches the desired type; if
the future contains an exception or the value cannot be cast to the desired
type, it will throw the exception or a :class:`ClassCastException` (if you want
to get :obj:`None` in the latter case, use :meth:`Future.asSilently[T]`). In
case of a timeout, :obj:`None` is returned.

.. code-block:: scala

  (actor ? msg).as[String] match {
    case Some(answer) => ...
    case None         => ...
  }

  val resultOption = (actor ? msg).as[String]
  if (resultOption.isDefined) ... else ...

  for (x <- (actor ? msg).as[Int]) yield { 2 * x }

Forward message
^^^^^^^^^^^^^^^

You can forward a message from one actor to another. This means that the original sender address/reference is maintained even though the message is going through a 'mediator'. This can be useful when writing actors that work as routers, load-balancers, replicators etc.

.. code-block:: scala

  actor.forward(message)

Receive messages
----------------

An Actor has to implement the ``receive`` method to receive messages:

.. code-block:: scala

  protected def receive: PartialFunction[Any, Unit]

Note: Akka has an alias to the ``PartialFunction[Any, Unit]`` type called ``Receive`` (``akka.actor.Actor.Receive``), so you can use this type instead for clarity. But most often you don't need to spell it out.

This method should return a ``PartialFunction``, e.g. a ‘match/case’ clause in which the message can be matched against the different case clauses using Scala pattern matching. Here is an example:

.. code-block:: scala

  class MyActor extends Actor {
    def receive = {
      case "Hello" =>
        log.info("Received 'Hello'")

      case _ =>
        throw new RuntimeException("unknown message")
    }
  }

Reply to messages
-----------------

Reply using the channel
^^^^^^^^^^^^^^^^^^^^^^^

If you want to have a handle to an object to whom you can reply to the message, you can use the ``Channel`` abstraction.
Simply call ``self.channel`` and then you can forward that to others, store it away or otherwise until you want to reply, which you do by ``channel ! response``:

.. code-block:: scala

  case request =>
      val result = process(request)
      self.channel ! result       // will throw an exception if there is no sender information
      self.channel tryTell result // will return Boolean whether reply succeeded

The :class:`Channel` trait is contravariant in the expected message type. Since
``self.channel`` is subtype of ``Channel[Any]``, you may specialise your return
channel to allow the compiler to check your replies::

  class MyActor extends Actor {
    def doIt(channel: Channel[String], x: Any) = { channel ! x.toString }
    def receive = {
      case x => doIt(self.channel, x)
    }
  }

.. code-block:: scala

  case request =>
      friend forward self.channel

We recommend that you as first choice use the channel abstraction instead of the other ways described in the following sections.

Reply using the reply and reply\_? methods
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you want to send a message back to the original sender of the message you just received then you can use the ``reply(..)`` method.

.. code-block:: scala

  case request =>
    val result = process(request)
    self.reply(result)

In this case the ``result`` will be send back to the Actor that sent the ``request``.

The ``reply`` method throws an ``IllegalStateException`` if unable to determine what to reply to, e.g. the sender is not an actor. You can also use the more forgiving ``tryReply`` method which returns ``true`` if reply was sent, and ``false`` if unable to determine what to reply to.

.. code-block:: scala

  case request =>
    val result = process(request)
    if (self.tryReply(result)) ...// success
    else ... // handle failure

Summary of reply semantics and options
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* ``self.reply(...)`` can be used to reply to an ``Actor`` or a ``Future`` from
  within an actor; the current actor will be passed as reply channel if the
  current channel supports this.
* ``self.channel`` is a reference providing an abstraction for the reply
  channel; this reference may be passed to other actors or used by non-actor
  code.

.. note::

  There used to be two methods for determining the sending Actor or Future for the current invocation:

  * ``self.sender`` yielded a :class:`Option[ActorRef]`
  * ``self.senderFuture`` yielded a :class:`Option[CompletableFuture[Any]]`

  These two concepts have been unified into the ``channel``. If you need to know the nature of the channel, you may do so using pattern matching::

    self.channel match {
      case ref : ActorRef => ...
      case f : ActorCompletableFuture => ...
    }

Initial receive timeout
-----------------------

A timeout mechanism can be used to receive a message when no initial message is received within a certain time. To receive this timeout you have to set the ``receiveTimeout`` property and declare a case handing the ReceiveTimeout object.

.. code-block:: scala

  self.receiveTimeout = Some(30000L) // 30 seconds

  def receive = {
    case "Hello" =>
      log.info("Received 'Hello'")
    case ReceiveTimeout =>
        throw new RuntimeException("received timeout")
  }

This mechanism also work for hotswapped receive functions. Every time a ``HotSwap`` is sent, the receive timeout is reset and rescheduled.

Starting actors
---------------

Actors are created & started by invoking the ``actorOf`` method.

.. code-block:: scala

  val actor = actorOf[MyActor]
  actor

When you create the ``Actor`` then it will automatically call the ``def preStart`` callback method on the ``Actor`` trait. This is an excellent place to add initialization code for the actor.

.. code-block:: scala

  override def preStart() = {
    ... // initialization code
  }

Stopping actors
---------------

Actors are stopped by invoking the ``stop`` method.

.. code-block:: scala

  actor.stop()

When stop is called then a call to the ``def postStop`` callback method will take place. The ``Actor`` can use this callback to implement shutdown behavior.

.. code-block:: scala

  override def postStop() = {
    ... // clean up resources
  }

You can shut down all Actors in the system by invoking:

.. code-block:: scala

  Actor.registry.shutdownAll()


PoisonPill
----------

You can also send an actor the ``akka.actor.PoisonPill`` message, which will stop the actor when the message is processed.

If the sender is a ``Future`` (e.g. the message is sent with ``?``), the ``Future`` will be completed with an ``akka.actor.ActorKilledException("PoisonPill")``.

.. _Actor.HotSwap:

HotSwap
-------

Upgrade
^^^^^^^

Akka supports hotswapping the Actor’s message loop (e.g. its implementation) at runtime. There are two ways you can do that:

* Send a ``HotSwap`` message to the Actor.
* Invoke the ``become`` method from within the Actor.

Both of these takes a ``ActorRef => PartialFunction[Any, Unit]`` that implements the new message handler. The hotswapped code is kept in a Stack which can be pushed and popped.

To hotswap the Actor body using the ``HotSwap`` message:

.. code-block:: scala

  actor ! HotSwap( self => {
    case message => self.reply("hotswapped body")
  })

Using the ``HotSwap`` message for hotswapping has its limitations. You can not replace it with any code that uses the Actor's ``self`` reference. If you need to do that the the ``become`` method is better.

To hotswap the Actor using ``become``:

.. code-block:: scala

  def angry: Receive = {
    case "foo" => self reply "I am already angry?"
    case "bar" => become(happy)
  }

  def happy: Receive = {
    case "bar" => self reply "I am already happy :-)"
    case "foo" => become(angry)
  }

  def receive = {
    case "foo" => become(angry)
    case "bar" => become(happy)
  }

The ``become`` method is useful for many different things, but a particular nice example of it is in example where it is used to implement a Finite State Machine (FSM): `Dining Hakkers <http://github.com/jboner/akka/blob/master/akka-samples/akka-sample-fsm/src/main/scala/DiningHakkersOnBecome.scala>`_

Here is another little cute example of ``become`` and ``unbecome`` in action:

.. code-block:: scala

  case object Swap
  class Swapper extends Actor {
   def receive = {
     case Swap =>
       println("Hi")
       become {
         case Swap =>
           println("Ho")
           unbecome() // resets the latest 'become' (just for fun)
       }
   }
  }

  val swap = actorOf[Swapper]

  swap ! Swap // prints Hi
  swap ! Swap // prints Ho
  swap ! Swap // prints Hi
  swap ! Swap // prints Ho
  swap ! Swap // prints Hi
  swap ! Swap // prints Ho

Encoding Scala Actors nested receives without accidentally leaking memory: `UnnestedReceive <https://gist.github.com/797035>`_
------------------------------------------------------------------------------------------------------------------------------

Downgrade
^^^^^^^^^

Since the hotswapped code is pushed to a Stack you can downgrade the code as well. There are two ways you can do that:

* Send the Actor a ``RevertHotswap`` message
* Invoke the ``unbecome`` method from within the Actor.

Both of these will pop the Stack and replace the Actor's implementation with the ``PartialFunction[Any, Unit]`` that is at the top of the Stack.

Revert the Actor body using the ``RevertHotSwap`` message:

.. code-block:: scala

  actor ! RevertHotSwap

Revert the Actor body using the ``unbecome`` method:

.. code-block:: scala

  def receive: Receive = {
    case "revert" => unbecome()
  }

Killing an Actor
----------------

You can kill an actor by sending a ``Kill`` message. This will restart the actor through regular supervisor semantics.

Use it like this:

.. code-block:: scala

  // kill the actor called 'victim'
  victim ! Kill

Actor life-cycle
----------------

The actor has a well-defined non-circular life-cycle.

::

  NEW (newly created actor) - can't receive messages (yet)
      => STARTED (when 'start' is invoked) - can receive messages
          => SHUT DOWN (when 'exit' or 'stop' is invoked) - can't do anything

Actors and exceptions
---------------------
It can happen that while a message is being processed by an actor, that some kind of exception is thrown, e.g. a
database exception.

What happens to the Message
^^^^^^^^^^^^^^^^^^^^^^^^^^^

If an exception is thrown while a message is being processed (so taken of his mailbox and handed over the the receive),
then this message will be lost. It is important to understand that it is not put back on the mailbox. So if you want to
retry processing of a message, you need to deal with it yourself by catching the exception and retry your flow. Make
sure that you put a bound on the number of retries since you don't want a system to livelock (so consuming a lot of
cpu cycles without making progress).

What happens to the mailbox
^^^^^^^^^^^^^^^^^^^^^^^^^^^
If an exception is thrown while a message is being processed, nothing happens to the mailbox. If the actor is restarted,
the same mailbox will be there. So all messages on that mailbox, will be there as well.

What happens to the actor
^^^^^^^^^^^^^^^^^^^^^^^^^
If an exception is thrown and the actor is supervised, the actor object itself is discarded and a new instance is
created. This new instance will now be used in the actor references to this actor (so this is done invisible
to the developer).
If the actor is _not_ supervised, but its lifeCycle is set to Permanent (default), it will just keep on processing messages as if nothing had happened.
If the actor is _not_ supervised, but its lifeCycle is set to Temporary, it will be stopped immediately.


Extending Actors using PartialFunction chaining
-----------------------------------------------

A bit advanced but very useful way of defining a base message handler and then extend that, either through inheritance or delegation, is to use ``PartialFunction.orElse`` chaining.

In generic base Actor:

.. code-block:: scala

  import akka.actor.Actor.Receive
  
  abstract class GenericActor extends Actor {
    // to be defined in subclassing actor
    def specificMessageHandler: Receive
   
    // generic message handler
    def genericMessageHandler: Receive = {
      case event => printf("generic: %s\n", event)
    }
   
    def receive = specificMessageHandler orElse genericMessageHandler
  }

In subclassing Actor:

.. code-block:: scala

  class SpecificActor extends GenericActor {
    def specificMessageHandler = {
      case event: MyMsg  => printf("specific: %s\n", event.subject)
    }
  }
  
  case class MyMsg(subject: String)
