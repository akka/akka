.. _dispatchers-java:

Dispatchers (Java)
===================

.. sidebar:: Contents

   .. contents:: :local:

The Dispatcher is an important piece that allows you to configure the right semantics and parameters for optimal performance, throughput and scalability. Different Actors have different needs.

Akka supports dispatchers for both event-driven lightweight threads, allowing creation of millions of threads on a single workstation, and thread-based Actors, where each dispatcher is bound to a dedicated OS thread.

The event-based Actors currently consume ~600 bytes per Actor which means that you can create more than 6.5 million Actors on 4 GB RAM.

Default dispatcher
------------------

For most scenarios the default settings are the best. Here we have one single event-based dispatcher for all Actors created.
The default dispatcher is available from the ``ActorSystem.dispatcher`` and can be configured in the ``akka.actor.default-dispatcher``
section of the :ref:`configuration`.

If you are starting to get contention on the single dispatcher (the ``Executor`` and its queue) or want to group a specific set of Actors
for a dedicated dispatcher for better flexibility and configurability then you can override the defaults and define your own dispatcher.
See below for details on which ones are available and how they can be configured.

Setting the dispatcher
----------------------

You specify the id of the dispatcher to use when creating an actor. The id corresponds to the :ref:`configuration` key
of the dispatcher settings.

.. includecode:: code/akka/docs/dispatcher/DispatcherDocTestBase.java
   :include: imports,defining-dispatcher

Types of dispatchers
--------------------

There are 4 different types of message dispatchers:

* Thread-based (Pinned)
* Event-based
* Priority event-based
* Work-sharing (Balancing)

It is recommended to define the dispatcher in :ref:`configuration` to allow for tuning for different environments.

Example of a custom event-based dispatcher, which can be used with
``new Props().withCreator(MyUntypedActor.class).withDispatcher("my-dispatcher")``
as in the example above:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-dispatcher-config

Default values are taken from ``default-dispatcher``, i.e. all options doesn't need to be defined. See
:ref:`configuration` for the default values of the ``default-dispatcher``. You can also override
the values for the ``default-dispatcher`` in your configuration.

.. note::

  It should be noted that the ``dispatcher-id`` used in :class:`Props` is in
  fact an absolute path into the configuration object, i.e. you can declare a
  dispatcher configuration nested within other configuration objects and refer
  to it like so: ``"my.config.object.myAwesomeDispatcher"``

There are two different executor services:

* executor = "fork-join-executor", ``ExecutorService`` based on ForkJoinPool (jsr166y). This is used by default for
  ``default-dispatcher``.
* executor = "thread-pool-executor", ``ExecutorService`` based on ``java.util.concurrent.ThreadPoolExecutor``.

Note that the pool size is configured differently for the two executor services. The configuration above
is an example for ``fork-join-executor``. Below is an example for ``thread-pool-executor``:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-thread-pool-dispatcher-config

Let's now walk through the different dispatchers in more detail.

Thread-based
^^^^^^^^^^^^

The ``PinnedDispatcher`` binds a dedicated OS thread to each specific Actor. The messages are posted to a
`LinkedBlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/LinkedBlockingQueue.html>`_
which feeds the messages to the dispatcher one by one. A ``PinnedDispatcher`` cannot be shared between actors. This dispatcher
has worse performance and scalability than the event-based dispatcher but works great for creating "daemon" Actors that consumes
a low frequency of messages and are allowed to go off and do their own thing for a longer period of time. Another advantage with
this dispatcher is that Actors do not block threads for each other.

The ``PinnedDispatcher`` is configured like this:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-pinned-dispatcher-config

Note that it must be used with ``executor = "thread-pool-executor"``.

Event-based
^^^^^^^^^^^

The event-based ``Dispatcher`` binds a set of Actors to a thread pool backed up by a
`BlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/BlockingQueue.html>`_. This dispatcher is highly configurable
and supports a fluent configuration API to configure the ``BlockingQueue`` (type of queue, max items etc.) as well as the thread pool.

The event-driven dispatchers **must be shared** between multiple Actors. One best practice is to let each top-level Actor, e.g.
the Actors you create from ``system.actorOf`` to get their own dispatcher but reuse the dispatcher for each new Actor
that the top-level Actor creates. But you can also share dispatcher between multiple top-level Actors. This is very use-case specific
and needs to be tried out on a case by case basis. The important thing is that Akka tries to provide you with the freedom you need to
design and implement your system in the most efficient way in regards to performance, throughput and latency.

It comes with many different predefined BlockingQueue configurations:

* Bounded `LinkedBlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/LinkedBlockingQueue.html>`_
* Unbounded `LinkedBlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/LinkedBlockingQueue.html>`_
* Bounded `ArrayBlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/ArrayBlockingQueue.html>`_
* Unbounded `ArrayBlockingQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/ArrayBlockingQueue.html>`_
* `SynchronousQueue <http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/SynchronousQueue.html>`_

When using a bounded queue and it has grown up to limit defined the message processing will run in the caller's
thread as a way to slow him down and balance producer/consumer.

Here is an example of a bounded mailbox:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-bounded-config

The standard :class:`Dispatcher` allows you to define the ``throughput`` it
should have, as shown above. This defines the number of messages for a specific
Actor the dispatcher should process in one single sweep; in other words, the
dispatcher will batch process up to ``throughput`` messages together when
having elected an actor to run.  Setting this to a higher number will increase
throughput but lower fairness, and vice versa. If you don't specify it explicitly
then it uses the value (5) defined for ``default-dispatcher`` in the :ref:`configuration`.

Browse the `ScalaDoc <scaladoc>`_ or look at the code for all the options available.

Priority event-based
^^^^^^^^^^^^^^^^^^^^

Sometimes it's useful to be able to specify priority order of messages, that is done by using Dispatcher and supply
an UnboundedPriorityMailbox or BoundedPriorityMailbox with a ``java.util.Comparator[Envelope]`` or use a
``akka.dispatch.PriorityGenerator`` (recommended).

Creating a Dispatcher with a mailbox using PriorityGenerator:

Config:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala
   :include: prio-dispatcher-config-java

Priority mailbox:

.. includecode:: code/akka/docs/dispatcher/DispatcherDocTestBase.java
   :include: imports-prio-mailbox,prio-mailbox

Usage:

.. includecode:: code/akka/docs/dispatcher/DispatcherDocTestBase.java
   :include: imports-prio,prio-dispatcher


Work-sharing event-based
^^^^^^^^^^^^^^^^^^^^^^^^^

The ``BalancingDispatcher`` is a variation of the ``Dispatcher`` in which Actors of the same type can be set up to
share this dispatcher and during execution time the different actors will steal messages from other actors if they
have less messages to process.
Although the technique used in this implementation is commonly known as "work stealing", the actual implementation is probably
best described as "work donating" because the actor of which work is being stolen takes the initiative.
This can be a great way to improve throughput at the cost of a little higher latency.

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-balancing-config

Here is an article with some more information: `Load Balancing Actors with Work Stealing Techniques <http://janvanbesien.blogspot.com/2010/03/load-balancing-actors-with-work.html>`_
Here is another article discussing this particular dispatcher: `Flexible load balancing with Akka in Scala <http://vasilrem.com/blog/software-development/flexible-load-balancing-with-akka-in-scala/>`_

Making the Actor mailbox bounded
--------------------------------

Global configuration
^^^^^^^^^^^^^^^^^^^^

You can make the Actor mailbox bounded by a capacity in two ways. Either you define it in the :ref:`configuration` file under
``default-dispatcher``. This will set it globally as default for the DefaultDispatcher and for other configured dispatchers,
if not specified otherwise.

.. code-block:: ruby

  akka {
    actor {
      default-dispatcher {
        # If negative (or zero) then an unbounded mailbox is used (default)
        # If positive then a bounded mailbox is used and the capacity is set to the number specified
        mailbox-capacity = 1000
      }
    }
  }

Per-instance based configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can also do it on a specific dispatcher instance.

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-bounded-config


For the ``PinnedDispatcher``, it is non-shareable between actors, and associates a dedicated Thread with the actor.
Making it bounded (by specifying a capacity) is optional, but if you do, you need to provide a pushTimeout (default is 10 seconds).
When trying to send a message to the Actor it will throw a MessageQueueAppendFailedException("BlockingMessageTransferQueue transfer timed out")
if the message cannot be added to the mailbox within the time specified by the pushTimeout.

