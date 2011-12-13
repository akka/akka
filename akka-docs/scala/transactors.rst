.. _transactors-scala:

Transactors (Scala)
===================

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

Why Transactors?
----------------

Actors are excellent for solving problems where you have many independent processes that can work in isolation and only interact with other Actors through message passing. This model fits many problems. But the actor model is unfortunately a terrible model for implementing truly shared state. E.g. when you need to have consensus and a stable view of state across many components. The classic example is the bank account where clients can deposit and withdraw, in which each operation needs to be atomic. For detailed discussion on the topic see `this JavaOne presentation <http://www.slideshare.net/jboner/state-youre-doing-it-wrong-javaone-2009>`_.

**STM** on the other hand is excellent for problems where you need consensus and a stable view of the state by providing compositional transactional shared state. Some of the really nice traits of STM are that transactions compose, and it raises the abstraction level from lock-based concurrency.

Akka's Transactors combine Actors and STM to provide the best of the Actor model (concurrency and asynchronous event-based programming) and STM (compositional transactional shared state) by providing transactional, compositional, asynchronous, event-based message flows.

If you need Durability then you should not use one of the in-memory data structures but one of the persistent ones.

Generally, the STM is not needed very often when working with Akka. Some use-cases (that we can think of) are:

- When you really need composable message flows across many actors updating their **internal local** state but need them to do that atomically in one big transaction. Might not often, but when you do need this then you are screwed without it.
- When you want to share a datastructure across actors.
- When you need to use the persistence modules.

Actors and STM
^^^^^^^^^^^^^^

You can combine Actors and STM in several ways. An Actor may use STM internally so that particular changes are guaranteed to be atomic. Actors may also share transactional datastructures as the STM provides safe shared state across threads.

It's also possible to coordinate transactions across Actors or threads so that either the transactions in a set all commit successfully or they all fail. This is the focus of Transactors and the explicit support for coordinated transactions in this section.


Coordinated transactions
------------------------

Akka provides an explicit mechanism for coordinating transactions across Actors. Under the hood it uses a ``CountDownCommitBarrier``, similar to a CountDownLatch.

Here is an example of coordinating two simple counter Actors so that they both increment together in coordinated transactions. If one of them was to fail to increment, the other would also fail.

.. code-block:: scala

  import akka.transactor.Coordinated
  import akka.stm.Ref
  import akka.actor.{Actor, ActorRef}

  case class Increment(friend: Option[ActorRef] = None)
  case object GetCount

  class Counter extends Actor {
    val count = Ref(0)

    def receive = {
      case coordinated @ Coordinated(Increment(friend)) => {
        friend foreach (_ ! coordinated(Increment()))
        coordinated atomic {
          count alter (_ + 1)
        }
      }
      case GetCount => self.reply(count.get)
    }
  }

  val counter1 = Actor.actorOf(Props[Counter])
  val counter2 = Actor.actorOf(Props[Counter])

  counter1 ! Coordinated(Increment(Some(counter2)))

  ...

  (counter1 ? GetCount).as[Int] // Some(1)

  counter1.stop()
  counter2.stop()

To start a new coordinated transaction that you will also participate in, just create a ``Coordinated`` object:

.. code-block:: scala

  val coordinated = Coordinated()

To start a coordinated transaction that you won't participate in yourself you can create a ``Coordinated`` object with a message and send it directly to an actor. The recipient of the message will be the first member of the coordination set:

.. code-block:: scala

  actor ! Coordinated(Message)

To receive a coordinated message in an actor simply match it in a case statement:

.. code-block:: scala

  def receive = {
    case coordinated @ Coordinated(Message) => ...
  }

To include another actor in the same coordinated transaction that you've created or received, use the apply method on that object. This will increment the number of parties involved by one and create a new ``Coordinated`` object to be sent.

.. code-block:: scala

  actor ! coordinated(Message)

To enter the coordinated transaction use the atomic method of the coordinated object:

.. code-block:: scala

  coordinated atomic {
    // do something in transaction ...
  }

The coordinated transaction will wait for the other transactions before committing. If any of the coordinated transactions fail then they all fail.


Transactor
----------

Transactors are actors that provide a general pattern for coordinating transactions, using the explicit coordination described above.

Here's an example of a simple transactor that will join a coordinated transaction:

.. code-block:: scala

  import akka.transactor.Transactor
  import akka.stm.Ref

  case object Increment

  class Counter extends Transactor {
    val count = Ref(0)

    override def atomically = {
      case Increment => count alter (_ + 1)
    }
  }

You could send this Counter transactor a ``Coordinated(Increment)`` message. If you were to send it just an ``Increment`` message it will create its own ``Coordinated`` (but in this particular case wouldn't be coordinating transactions with any other transactors).

To coordinate with other transactors override the ``coordinate`` method. The ``coordinate`` method maps a message to a set of ``SendTo`` objects, pairs of ``ActorRef`` and a message. You can use the ``include`` and ``sendTo`` methods to easily coordinate with other transactors. The ``include`` method will send on the same message that was received to other transactors. The ``sendTo`` method allows you to specify both the actor to send to, and the message to send.

Example of coordinating an increment:

.. code-block:: scala

  import akka.transactor.Transactor
  import akka.stm.Ref
  import akka.actor.ActorRef

  case object Increment

  class FriendlyCounter(friend: ActorRef) extends Transactor {
    val count = Ref(0)

    override def coordinate = {
      case Increment => include(friend)
    }

    override def atomically = {
      case Increment => count alter (_ + 1)
    }
  }

Using ``include`` to include more than one transactor:

.. code-block:: scala

  override def coordinate = {
    case Message => include(actor1, actor2, actor3)
  }

Using ``sendTo`` to coordinate transactions but pass-on a different message than the one that was received:

.. code-block:: scala

  override def coordinate = {
    case Message => sendTo(someActor -> SomeOtherMessage)
    case SomeMessage => sendTo(actor1 -> Message1, actor2 -> Message2)
  }

To execute directly before or after the coordinated transaction, override the ``before`` and ``after`` methods. These methods also expect partial functions like the receive method. They do not execute within the transaction.

To completely bypass coordinated transactions override the ``normally`` method. Any message matched by ``normally`` will not be matched by the other methods, and will not be involved in coordinated transactions. In this method you can implement normal actor behavior, or use the normal STM atomic for local transactions.


Coordinating Typed Actors
-------------------------

It's also possible to use coordinated transactions with typed actors. You can explicitly pass around ``Coordinated`` objects, or use built-in support with the ``@Coordinated`` annotation and the ``Coordination.coordinate`` method.

To specify a method should use coordinated transactions add the ``@Coordinated`` annotation. **Note**: the ``@Coordinated`` annotation only works with methods that return Unit (one-way methods).

.. code-block:: scala

  trait Counter {
    @Coordinated def increment()
    def get: Int
  }

To coordinate transactions use a ``coordinate`` block:

.. code-block:: scala

  coordinate {
    counter1.increment()
    counter2.increment()
  }

Here's an example of using ``@Coordinated`` with a TypedActor to coordinate increments.

.. code-block:: scala

  import akka.actor.TypedActor
  import akka.stm.Ref
  import akka.transactor.annotation.Coordinated
  import akka.transactor.Coordination._

  trait Counter {
    @Coordinated def increment()
    def get: Int
  }

  class CounterImpl extends TypedActor with Counter {
    val ref = Ref(0)
    def increment() { ref alter (_ + 1) }
    def get = ref.get
  }

  ...

  val counter1 = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])
  val counter2 = TypedActor.newInstance(classOf[Counter], classOf[CounterImpl])

  coordinate {
    counter1.increment()
    counter2.increment()
  }

  TypedActor.stop(counter1)
  TypedActor.stop(counter2)

The ``coordinate`` block will wait for the transactions to complete. If you do not want to wait then you can specify this explicitly:

.. code-block:: scala

  coordinate(wait = false) {
    counter1.increment()
    counter2.increment()
  }

