.. _transactors-java:

####################
 Transactors (Java)
####################

.. sidebar:: Contents

   .. contents:: :local:


Why Transactors?
================

Actors are excellent for solving problems where you have many independent
processes that can work in isolation and only interact with other Actors through
message passing. This model fits many problems. But the actor model is
unfortunately a terrible model for implementing truly shared state. E.g. when
you need to have consensus and a stable view of state across many
components. The classic example is the bank account where clients can deposit
and withdraw, in which each operation needs to be atomic. For detailed
discussion on the topic see `this JavaOne presentation
<http://www.slideshare.net/jboner/state-youre-doing-it-wrong-javaone-2009>`_.

STM on the other hand is excellent for problems where you need consensus and a
stable view of the state by providing compositional transactional shared
state. Some of the really nice traits of STM are that transactions compose, and
it raises the abstraction level from lock-based concurrency.

Akka's Transactors combine Actors and STM to provide the best of the Actor model
(concurrency and asynchronous event-based programming) and STM (compositional
transactional shared state) by providing transactional, compositional,
asynchronous, event-based message flows.

Generally, the STM is not needed very often when working with Akka. Some
use-cases (that we can think of) are:

- When you really need composable message flows across many actors updating
  their **internal local** state but need them to do that atomically in one big
  transaction. Might not be often but when you do need this then you are
  screwed without it.

- When you want to share a datastructure across actors.


Actors and STM
==============

You can combine Actors and STM in several ways. An Actor may use STM internally
so that particular changes are guaranteed to be atomic. Actors may also share
transactional datastructures as the STM provides safe shared state across
threads.

It's also possible to coordinate transactions across Actors or threads so that
either the transactions in a set all commit successfully or they all fail. This
is the focus of Transactors and the explicit support for coordinated
transactions in this section.


Coordinated transactions
========================

Akka provides an explicit mechanism for coordinating transactions across
actors. Under the hood it uses a ``CommitBarrier``, similar to a CountDownLatch.

Here is an example of coordinating two simple counter UntypedActors so that they
both increment together in coordinated transactions. If one of them was to fail
to increment, the other would also fail.

.. includecode:: code/akka/docs/transactor/Increment.java#class
   :language: java

.. includecode:: code/akka/docs/transactor/CoordinatedCounter.java#class
   :language: java

.. includecode:: code/akka/docs/transactor/TransactorDocTest.java#imports
   :language: java

.. includecode:: code/akka/docs/transactor/TransactorDocTest.java#coordinated-example
   :language: java

To start a new coordinated transaction that you will also participate in, create
a ``Coordinated`` object, passing in a ``Timeout``:

.. includecode:: code/akka/docs/transactor/TransactorDocTest.java#create-coordinated
   :language: java

To start a coordinated transaction that you won't participate in yourself you
can create a ``Coordinated`` object with a message and send it directly to an
actor. The recipient of the message will be the first member of the coordination
set:

.. includecode:: code/akka/docs/transactor/TransactorDocTest.java#send-coordinated
   :language: java

To include another actor in the same coordinated transaction that you've created
or received, use the ``coordinate`` method on that object. This will increment
the number of parties involved by one and create a new ``Coordinated`` object to
be sent.

.. includecode:: code/akka/docs/transactor/TransactorDocTest.java#include-coordinated
   :language: java

To enter the coordinated transaction use the atomic method of the coordinated
object, passing in a ``java.lang.Runnable``.

.. includecode:: code/akka/docs/transactor/Coordinator.java#coordinated-atomic
   :language: java

The coordinated transaction will wait for the other transactions before
committing. If any of the coordinated transactions fail then they all fail.

.. note::

   The same actor should not be added to a coordinated transaction more than
   once. The transaction will not be able to complete as an actor only processes
   a single message at a time. When processing the first message the coordinated
   transaction will wait for the commit barrier, which in turn needs the second
   message to be received to proceed.


UntypedTransactor
=================

UntypedTransactors are untyped actors that provide a general pattern for
coordinating transactions, using the explicit coordination described above.

Here's an example of a simple untyped transactor that will join a coordinated
transaction:

.. includecode:: code/akka/docs/transactor/Counter.java#class
   :language: java

You could send this Counter transactor a ``Coordinated(Increment)`` message. If
you were to send it just an ``Increment`` message it will create its own
``Coordinated`` (but in this particular case wouldn't be coordinating
transactions with any other transactors).

To coordinate with other transactors override the ``coordinate`` method. The
``coordinate`` method maps a message to a set of ``SendTo`` objects, pairs of
``ActorRef`` and a message. You can use the ``include`` and ``sendTo`` methods
to easily coordinate with other transactors.

Here's an example of coordinating an increment, using an untyped transactor,
similar to the explicitly coordinated example above.

.. includecode:: code/akka/docs/transactor/FriendlyCounter.java#class
   :language: java

To execute directly before or after the coordinated transaction, override the
``before`` and ``after`` methods. They do not execute within the transaction.

To completely bypass coordinated transactions override the ``normally``
method. Any message matched by ``normally`` will not be matched by the other
methods, and will not be involved in coordinated transactions. In this method
you can implement normal actor behavior, or use the normal STM atomic for local
transactions.
