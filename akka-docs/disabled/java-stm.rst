.. _stm-java:

Software Transactional Memory (Java)
====================================

.. sidebar:: Contents

   .. contents:: :local:
   
Overview of STM
---------------

An `STM <http://en.wikipedia.org/wiki/Software_transactional_memory>`_ turns the Java heap into a transactional data set with begin/commit/rollback semantics. Very much like a regular database. It implements the first three letters in ACID; ACI:
* (failure) Atomicity: all changes during the execution of a transaction make it, or none make it. This only counts for transactional datastructures.
* Consistency: a transaction gets a consistent of reality (in Akka you get the Oracle version of the SERIALIZED isolation level).
* Isolated: changes made by concurrent execution transactions are not visible to each other.

Generally, the STM is not needed that often when working with Akka. Some use-cases (that we can think of) are:

- When you really need composable message flows across many actors updating their **internal local** state but need them to do that atomically in one big transaction. Might not often, but when you do need this then you are screwed without it.
- When you want to share a datastructure across actors.
- When you need to use the persistence modules.

Akka’s STM implements the concept in `Clojure’s <http://clojure.org/>`_ STM view on state in general. Please take the time to read `this excellent document <http://clojure.org/state>`_ and view `this presentation <http://www.infoq.com/presentations/Value-Identity-State-Rich-Hickey>`_ by Rich Hickey (the genius behind Clojure), since it forms the basis of Akka’s view on STM and state in general.

The STM is based on Transactional References (referred to as Refs). Refs are memory cells, holding an (arbitrary) immutable value, that implement CAS (Compare-And-Swap) semantics and are managed and enforced by the STM for coordinated changes across many Refs. They are implemented using the excellent `Multiverse STM <http://multiverse.codehaus.org/overview.html>`_.

Working with immutable collections can sometimes give bad performance due to extensive copying. Scala provides so-called persistent datastructures which makes working with immutable collections fast. They are immutable but with constant time access and modification. The use of structural sharing and an insert or update does not ruin the old structure, hence “persistent”. Makes working with immutable composite types fast. The persistent datastructures currently consist of a Map and Vector.

Simple example
--------------

Here is a simple example of an incremental counter using STM. This shows creating a ``Ref``, a transactional reference, and then modifying it within a transaction, which is delimited by an ``Atomic`` anonymous inner class.

.. code-block:: java

  import akka.stm.*;

  final Ref<Integer> ref = new Ref<Integer>(0);

  public int counter() {
      return new Atomic<Integer>() {
          public Integer atomically() {
              int inc = ref.get() + 1;
              ref.set(inc);
              return inc;
          }
      }.execute();
  }

  counter();
  // -> 1

  counter();
  // -> 2


Ref
---

Refs (transactional references) are mutable references to values and through the STM allow the safe sharing of mutable data. To ensure safety the value stored in a Ref should be immutable. The value referenced by a Ref can only be accessed or swapped within a transaction. Refs separate identity from value.

Creating a Ref
^^^^^^^^^^^^^^

You can create a Ref with or without an initial value.

.. code-block:: java

  import akka.stm.*;

  // giving an initial value
  final Ref<Integer> ref = new Ref<Integer>(0);

  // specifying a type but no initial value
  final Ref<Integer> ref = new Ref<Integer>();

Accessing the value of a Ref
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Use ``get`` to access the value of a Ref. Note that if no initial value has been given then the value is initially ``null``.

.. code-block:: java

  import akka.stm.*;

  final Ref<Integer> ref = new Ref<Integer>(0);

  Integer value = new Atomic<Integer>() {
      public Integer atomically() {
          return ref.get();
      }
  }.execute();
  // -> value = 0

Changing the value of a Ref
^^^^^^^^^^^^^^^^^^^^^^^^^^^

To set a new value for a Ref you can use ``set`` (or equivalently ``swap``), which sets the new value and returns the old value.

.. code-block:: java

  import akka.stm.*;

  final Ref<Integer> ref = new Ref<Integer>(0);

  new Atomic() {
      public Object atomically() {
          return ref.set(5);
      }
  }.execute();


Transactions
------------

A transaction is delimited using an ``Atomic`` anonymous inner class.

.. code-block:: java

  new Atomic() {
      public Object atomically() {
          // ...
      }
  }.execute();

All changes made to transactional objects are isolated from other changes, all make it or non make it (so failure atomicity) and are consistent. With the AkkaSTM you automatically have the Oracle version of the SERIALIZED isolation level, lower isolation is not possible. To make it fully serialized, set the writeskew property that checks if a writeskew problem is allowed to happen.

Retries
^^^^^^^

A transaction is automatically retried when it runs into some read or write conflict, until the operation completes, an exception (throwable) is thrown or when there are too many retries. When a read or writeconflict is encountered, the transaction uses a bounded exponential backoff to prevent cause more contention and give other transactions some room to complete.

If you are using non transactional resources in an atomic block, there could be problems because a transaction can be retried. If you are using print statements or logging, it could be that they are called more than once. So you need to be prepared to deal with this. One of the possible solutions is to work with a deferred or compensating task that is executed after the transaction aborts or commits.

Unexpected retries
^^^^^^^^^^^^^^^^^^

It can happen for the first few executions that you get a few failures of execution that lead to unexpected retries, even though there is not any read or writeconflict. The cause of this is that speculative transaction configuration/selection is used. There are transactions optimized for a single transactional object, for 1..n and for n to unlimited. So based on the execution of the transaction, the system learns; it begins with a cheap one and upgrades to more expensive ones. Once it has learned, it will reuse this knowledge. It can be activated/deactivated using the speculative property on the TransactionFactoryBuilder. In most cases it is best use the default value (enabled) so you get more out of performance.

Coordinated transactions and Transactors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you need coordinated transactions across actors or threads then see :ref:`transactors-java`.

Configuring transactions
^^^^^^^^^^^^^^^^^^^^^^^^

It's possible to configure transactions. The ``Atomic`` class can take a ``TransactionFactory``, which can determine properties of the transaction. A default transaction factory is used if none is specified. You can create a ``TransactionFactory`` with a ``TransactionFactoryBuilder``.

Configuring transactions with a ``TransactionFactory``:

.. code-block:: java

  import akka.stm.*;

  TransactionFactory txFactory = new TransactionFactoryBuilder()
      .setReadonly(true)
      .build();

  new Atomic<Object>(txFactory) {
      public Object atomically() {
          // read only transaction
          return ...;
      }
  }.execute();

The following settings are possible on a TransactionFactory:

- familyName - Family name for transactions. Useful for debugging because the familyName is shown in exceptions, logging and in the future also will be used for profiling.
- readonly - Sets transaction as readonly. Readonly transactions are cheaper and can be used to prevent modification to transactional objects.
- maxRetries - The maximum number of times a transaction will retry.
- timeout - The maximum time a transaction will block for.
- trackReads - Whether all reads should be tracked. Needed for blocking operations. Readtracking makes a transaction more expensive, but makes subsequent reads cheaper and also lowers the chance of a readconflict.
- writeSkew - Whether writeskew is allowed. Disable with care.
- blockingAllowed - Whether explicit retries are allowed.
- interruptible - Whether a blocking transaction can be interrupted if it is blocked.
- speculative - Whether speculative configuration should be enabled.
- quickRelease - Whether locks should be released as quickly as possible (before whole commit).
- propagation - For controlling how nested transactions behave.
- traceLevel - Transaction trace level.

You can also specify the default values for some of these options in :ref:`configuration`.

Transaction lifecycle listeners
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

It's possible to have code that will only run on the successful commit of a transaction, or when a transaction aborts. You can do this by adding ``deferred`` or ``compensating`` blocks to a transaction.

.. code-block:: java

  import akka.stm.*;
  import static akka.stm.StmUtils.deferred;
  import static akka.stm.StmUtils.compensating;

  new Atomic() {
      public Object atomically() {
          deferred(new Runnable() {
              public void run() {
                  // executes when transaction commits
              }
          });
          compensating(new Runnable() {
              public void run() {
                  // executes when transaction aborts
              }
          });
          // ...
          return something;
      }
  }.execute();

Blocking transactions
^^^^^^^^^^^^^^^^^^^^^

You can block in a transaction until a condition is met by using an explicit ``retry``. To use ``retry`` you also need to configure the transaction to allow explicit retries.

Here is an example of using ``retry`` to block until an account has enough money for a withdrawal. This is also an example of using actors and STM together.

.. code-block:: java

  import akka.stm.*;

  public class Transfer {
    private final Ref<Double> from;
    private final Ref<Double> to;
    private final double amount;

    public Transfer(Ref<Double> from, Ref<Double> to, double amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }

    public Ref<Double> getFrom() { return from; }
    public Ref<Double> getTo() { return to; }
    public double getAmount() { return amount; }
  }

.. code-block:: java

  import akka.stm.*;
  import static akka.stm.StmUtils.retry;
  import akka.actor.*;
  import akka.util.FiniteDuration;
  import java.util.concurrent.TimeUnit;
  import akka.event.EventHandler;

  public class Transferer extends UntypedActor {
      TransactionFactory txFactory = new TransactionFactoryBuilder()
          .setBlockingAllowed(true)
          .setTrackReads(true)
          .setTimeout(new FiniteDuration(60, TimeUnit.SECONDS))
          .build();

      public void onReceive(Object message) throws Exception {
          if (message instanceof Transfer) {
              Transfer transfer = (Transfer) message;
              final Ref<Double> from = transfer.getFrom();
              final Ref<Double> to = transfer.getTo();
              final double amount = transfer.getAmount();
              new Atomic(txFactory) {
                  public Object atomically() {
                      if (from.get() < amount) {
                          EventHandler.info(this, "not enough money - retrying");
                          retry();
                      }
                      EventHandler.info(this, "transferring");
                      from.set(from.get() - amount);
                      to.set(to.get() + amount);
                      return null;
                  }
              }.execute();
          }
      }
  }

.. code-block:: java

  import akka.stm.*;
  import akka.actor.*;

  public class Main {
    public static void main(String...args) throws Exception {
      final Ref<Double> account1 = new Ref<Double>(100.0);
      final Ref<Double> account2 = new Ref<Double>(100.0);

      ActorRef transferer = Actors.actorOf(Transferer.class);

      transferer.tell(new Transfer(account1, account2, 500.0));
      // Transferer: not enough money - retrying

      new Atomic() {
          public Object atomically() {
          return account1.set(account1.get() + 2000);
          }
      }.execute();
      // Transferer: transferring

      Thread.sleep(1000);

      Double acc1 = new Atomic<Double>() {
          public Double atomically() {
          return account1.get();
          }
      }.execute();

      Double acc2 = new Atomic<Double>() {
          public Double atomically() {
          return account2.get();
          }
      }.execute();



      System.out.println("Account 1: " + acc1);
      // Account 1: 1600.0

      System.out.println("Account 2: " + acc2);
      // Account 2: 600.0

      transferer.stop();
    }
  }

Alternative blocking transactions
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can also have two alternative blocking transactions, one of which can succeed first, with ``EitherOrElse``.

.. code-block:: java

  import akka.stm.*;

  public class Branch {
    private final Ref<Integer> left;
    private final Ref<Integer> right;
    private final double amount;

    public Branch(Ref<Integer> left, Ref<Integer> right, int amount) {
        this.left = left;
        this.right = right;
        this.amount = amount;
    }

    public Ref<Integer> getLeft() { return left; }

    public Ref<Integer> getRight() { return right; }

    public double getAmount() { return amount; }
  }

.. code-block:: java

  import akka.actor.*;
  import akka.stm.*;
  import static akka.stm.StmUtils.retry;
  import akka.util.FiniteDuration;
  import java.util.concurrent.TimeUnit;
  import akka.event.EventHandler;

  public class Brancher extends UntypedActor {
      TransactionFactory txFactory = new TransactionFactoryBuilder()
          .setBlockingAllowed(true)
          .setTrackReads(true)
          .setTimeout(new FiniteDuration(60, TimeUnit.SECONDS))
          .build();

      public void onReceive(Object message) throws Exception {
          if (message instanceof Branch) {
              Branch branch = (Branch) message;
              final Ref<Integer> left = branch.getLeft();
              final Ref<Integer> right = branch.getRight();
              final double amount = branch.getAmount();
              new Atomic<Integer>(txFactory) {
                  public Integer atomically() {
                      return new EitherOrElse<Integer>() {
                          public Integer either() {
                              if (left.get() < amount) {
                                  EventHandler.info(this, "not enough on left - retrying");
                                  retry();
                              }
                              EventHandler.info(this, "going left");
                              return left.get();
                          }
                          public Integer orElse() {
                              if (right.get() < amount) {
                                  EventHandler.info(this, "not enough on right - retrying");
                                  retry();
                              }
                              EventHandler.info(this, "going right");
                              return right.get();
                          }
                      }.execute();
                  }
              }.execute();
          }
      }
  }

.. code-block:: java

  import akka.stm.*;
  import akka.actor.*;

  public class Main2 {
    public static void main(String...args) throws Exception {
      final Ref<Integer> left = new Ref<Integer>(100);
      final Ref<Integer> right = new Ref<Integer>(100);

      ActorRef brancher = Actors.actorOf(Brancher.class);

      brancher.tell(new Branch(left, right, 500));
      // not enough on left - retrying
      // not enough on right - retrying

      Thread.sleep(1000);

      new Atomic() {
          public Object atomically() {
              return right.set(right.get() + 1000);
          }
      }.execute();
      // going right



      brancher.stop();
    }
  }


Transactional datastructures
----------------------------

Akka provides two datastructures that are managed by the STM.

- TransactionalMap
- TransactionalVector

TransactionalMap and TransactionalVector look like regular mutable datastructures, they even implement the standard Scala 'Map' and 'RandomAccessSeq' interfaces, but they are implemented using persistent datastructures and managed references under the hood. Therefore they are safe to use in a concurrent environment. Underlying TransactionalMap is HashMap, an immutable Map but with near constant time access and modification operations. Similarly TransactionalVector uses a persistent Vector. See the Persistent Datastructures section below for more details.

Like managed references, TransactionalMap and TransactionalVector can only be modified inside the scope of an STM transaction.

Here is an example of creating and accessing a TransactionalMap:

.. code-block:: java

  import akka.stm.*;

  // assuming a User class

  final TransactionalMap<String, User> users = new TransactionalMap<String, User>();

  // fill users map (in a transaction)
  new Atomic() {
      public Object atomically() {
          users.put("bill", new User("bill"));
          users.put("mary", new User("mary"));
          users.put("john", new User("john"));
          return null;
      }
  }.execute();

  // access users map (in a transaction)
  User user = new Atomic<User>() {
      public User atomically() {
          return users.get("bill").get();
      }
  }.execute();

Here is an example of creating and accessing a TransactionalVector:

.. code-block:: java

  import akka.stm.*;

  // assuming an Address class

  final TransactionalVector<Address> addresses = new TransactionalVector<Address>();

  // fill addresses vector (in a transaction)
  new Atomic() {
      public Object atomically() {
          addresses.add(new Address("somewhere"));
          addresses.add(new Address("somewhere else"));
          return null;
      }
  }.execute();

  // access addresses vector (in a transaction)
  Address address = new Atomic<Address>() {
      public Address atomically() {
          return addresses.get(0);
      }
  }.execute();


Persistent datastructures
-------------------------

Akka's STM should only be used with immutable data. This can be costly if you have large datastructures and are using a naive copy-on-write. In order to make working with immutable datastructures fast enough Scala provides what are called Persistent Datastructures. There are currently two different ones:

- HashMap (`scaladoc <http://www.scala-lang.org/api/current/scala/collection/immutable/HashMap.html>`__)
- Vector (`scaladoc <http://www.scala-lang.org/api/current/scala/collection/immutable/Vector.html>`__)

They are immutable and each update creates a completely new version but they are using clever structural sharing in order to make them almost as fast, for both read and update, as regular mutable datastructures.

This illustration is taken from Rich Hickey's presentation. Copyright Rich Hickey 2009.

.. image:: ../images/clojure-trees.png


