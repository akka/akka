.. _transactors-java:

Transactors (Java)
==================

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

Akka provides an explicit mechanism for coordinating transactions across actors. Under the hood it uses a ``CountDownCommitBarrier``, similar to a CountDownLatch.

Here is an example of coordinating two simple counter UntypedActors so that they both increment together in coordinated transactions. If one of them was to fail to increment, the other would also fail.

.. code-block:: java

  import akka.actor.ActorRef;

  public class Increment {
      private final ActorRef friend;

      public Increment() {
        this.friend = null;
      }

      public Increment(ActorRef friend) {
          this.friend = friend;
      }

      public boolean hasFriend() {
          return friend != null;
      }

      public ActorRef getFriend() {
          return friend;
      }
  }

.. code-block:: java

  import akka.actor.UntypedActor;
  import akka.stm.Ref;
  import akka.transactor.Atomically;
  import akka.transactor.Coordinated;

  public class Counter extends UntypedActor {
      private Ref<Integer> count = new Ref(0);

      private void increment() {
          count.set(count.get() + 1);
      }

      public void onReceive(Object incoming) throws Exception {
          if (incoming instanceof Coordinated) {
              Coordinated coordinated = (Coordinated) incoming;
              Object message = coordinated.getMessage();
              if (message instanceof Increment) {
                  Increment increment = (Increment) message;
                  if (increment.hasFriend()) {
                      increment.getFriend().tell(coordinated.coordinate(new Increment()));
                  }
                  coordinated.atomic(new Atomically() {
                      public void atomically() {
                          increment();
                      }
                  });
              }
          } else if (incoming.equals("GetCount")) {
              getContext().reply(count.get());
          }
      }
  }

.. code-block:: java

  ActorRef counter1 = actorOf(Counter.class);
  ActorRef counter2 = actorOf(Counter.class);

  counter1.tell(new Coordinated(new Increment(counter2)));

To start a new coordinated transaction that you will also participate in, just create a ``Coordinated`` object:

.. code-block:: java

  Coordinated coordinated = new Coordinated();

To start a coordinated transaction that you won't participate in yourself you can create a ``Coordinated`` object with a message and send it directly to an actor. The recipient of the message will be the first member of the coordination set:

.. code-block:: java

  actor.tell(new Coordinated(new Message()));

To include another actor in the same coordinated transaction that you've created or received, use the ``coordinate`` method on that object. This will increment the number of parties involved by one and create a new ``Coordinated`` object to be sent.

.. code-block:: java

  actor.tell(coordinated.coordinate(new Message()));

To enter the coordinated transaction use the atomic method of the coordinated object. This accepts either an ``akka.transactor.Atomically`` object, or an ``Atomic`` object the same as used normally in the STM (just don't execute it - the coordination will do that).

.. code-block:: java

  coordinated.atomic(new Atomically() {
      public void atomically() {
          // do something in a transaction
      }
  });

The coordinated transaction will wait for the other transactions before committing. If any of the coordinated transactions fail then they all fail.


UntypedTransactor
-----------------

UntypedTransactors are untyped actors that provide a general pattern for coordinating transactions, using the explicit coordination described above.

Here's an example of a simple untyped transactor that will join a coordinated transaction:

.. code-block:: java

  import akka.transactor.UntypedTransactor;
  import akka.stm.Ref;

  public class Counter extends UntypedTransactor {
      Ref<Integer> count = new Ref<Integer>(0);

      @Override
      public void atomically(Object message) {
          if (message instanceof Increment) {
              count.set(count.get() + 1);
          }
      }
  }

You could send this Counter transactor a ``Coordinated(Increment)`` message. If you were to send it just an ``Increment`` message it will create its own ``Coordinated`` (but in this particular case wouldn't be coordinating transactions with any other transactors).

To coordinate with other transactors override the ``coordinate`` method. The ``coordinate`` method maps a message to a set of ``SendTo`` objects, pairs of ``ActorRef`` and a message. You can use the ``include`` and ``sendTo`` methods to easily coordinate with other transactors.

Example of coordinating an increment, similar to the explicitly coordinated example:

.. code-block:: java

  import akka.transactor.UntypedTransactor;
  import akka.transactor.SendTo;
  import akka.stm.Ref;

  import java.util.Set;

  public class Counter extends UntypedTransactor {
      Ref<Integer> count = new Ref<Integer>(0);

      @Override
      public Set<SendTo> coordinate(Object message) {
          if (message instanceof Increment) {
              Increment increment = (Increment) message;
              if (increment.hasFriend())
                  return include(increment.getFriend(), new Increment());
          }
          return nobody();
      }

      @Override
      public void atomically(Object message) {
          if (message instanceof Increment) {
              count.set(count.get() + 1);
          }
      }
  }

To execute directly before or after the coordinated transaction, override the ``before`` and ``after`` methods. They do not execute within the transaction.

To completely bypass coordinated transactions override the ``normally`` method. Any message matched by ``normally`` will not be matched by the other methods, and will not be involved in coordinated transactions. In this method you can implement normal actor behavior, or use the normal STM atomic for local transactions.


Coordinating Typed Actors
-------------------------

It's also possible to use coordinated transactions with typed actors. You can explicitly pass around ``Coordinated`` objects, or use built-in support with the ``@Coordinated`` annotation and the ``Coordination.coordinate`` method.

To specify a method should use coordinated transactions add the ``@Coordinated`` annotation. **Note**: the ``@Coordinated`` annotation will only work with void (one-way) methods.

.. code-block:: java

  public interface Counter {
      @Coordinated public void increment();
      public Integer get();
  }

To coordinate transactions use a ``coordinate`` block. This accepts either an ``akka.transactor.Atomically`` object, or an ``Atomic`` object liked used in the STM (but don't execute it). The first boolean parameter specifies whether or not to wait for the transactions to complete.

.. code-block:: java

  Coordination.coordinate(true, new Atomically() {
      public void atomically() {
          counter1.increment();
          counter2.increment();
      }
  });

Here's an example of using ``@Coordinated`` with a TypedActor to coordinate increments:

.. code-block:: java

  import akka.transactor.annotation.Coordinated;

  public interface Counter {
      @Coordinated public void increment();
      public Integer get();
  }

.. code-block:: java

  import akka.actor.TypedActor;
  import akka.stm.Ref;

  public class CounterImpl extends TypedActor implements Counter {
      private Ref<Integer> count = new Ref<Integer>(0);

      public void increment() {
          count.set(count.get() + 1);
      }

      public Integer get() {
          return count.get();
      }
  }

.. code-block:: java

  Counter counter1 = (Counter) TypedActor.newInstance(Counter.class, CounterImpl.class);
  Counter counter2 = (Counter) TypedActor.newInstance(Counter.class, CounterImpl.class);

  Coordination.coordinate(true, new Atomically() {
    public void atomically() {
      counter1.increment();
      counter2.increment();
    }
  });

  TypedActor.stop(counter1);
  TypedActor.stop(counter2);

