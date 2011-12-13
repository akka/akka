.. _dispatchers-scala:

Dispatchers (Scala)
===================

.. sidebar:: Contents

   .. contents:: :local:
   
Module stability: **SOLID**

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

You specify the dispatcher to use when creating an actor.

.. includecode:: code/DispatcherDocSpec.scala
   :include: imports,defining-dispatcher

Types of dispatchers
--------------------

There are 4 different types of message dispatchers:

* Thread-based (Pinned)
* Event-based
* Priority event-based
* Work-stealing (Balancing)

It is recommended to define the dispatcher in :ref:`configuration` to allow for tuning for different environments.

Example of a custom event-based dispatcher, which can be fetched with ``system.dispatcherFactory.lookup("my-dispatcher")`` 
as in the example above: 

.. includecode:: code/DispatcherDocSpec.scala#my-dispatcher-config

Default values are taken from ``default-dispatcher``, i.e. all options doesn't need to be defined.

.. warning::

  Factory methods for creating dispatchers programmatically are available in ``akka.dispatch.Dispatchers``, i.e.
  ``dispatcherFactory`` of the ``ActorSystem``. These methods will probably be changed or removed before 
  2.0 final release, because dispatchers need to be defined by configuration to work in a clustered setup.

Let's now walk through the different dispatchers in more detail.

Thread-based
^^^^^^^^^^^^

The ``PinnedDispatcher`` binds a dedicated OS thread to each specific Actor. The messages are posted to a ``LinkedBlockingQueue`` 
which feeds the messages to the dispatcher one by one. A ``PinnedDispatcher`` cannot be shared between actors. This dispatcher 
has worse performance and scalability than the event-based dispatcher but works great for creating "daemon" Actors that consumes 
a low frequency of messages and are allowed to go off and do their own thing for a longer period of time. Another advantage with 
this dispatcher is that Actors do not block threads for each other.

FIXME PN: Is this the way to configure a PinnedDispatcher, and then why "A ``PinnedDispatcher`` cannot be shared between actors."

The ``PinnedDispatcher`` is configured as a event-based dispatcher with with core pool size of 1.

.. includecode:: code/DispatcherDocSpec.scala#my-pinned-config

Event-based
^^^^^^^^^^^

The event-based ``Dispatcher`` binds a set of Actors to a thread pool backed up by a ``BlockingQueue``. This dispatcher is highly configurable 
and supports a fluent configuration API to configure the ``BlockingQueue`` (type of queue, max items etc.) as well as the thread pool.

The event-driven dispatchers **must be shared** between multiple Actors. One best practice is to let each top-level Actor, e.g. 
the Actors you create from ``system.actorOf`` to get their own dispatcher but reuse the dispatcher for each new Actor 
that the top-level Actor creates. But you can also share dispatcher between multiple top-level Actors. This is very use-case specific 
and needs to be tried out on a case by case basis. The important thing is that Akka tries to provide you with the freedom you need to 
design and implement your system in the most efficient way in regards to performance, throughput and latency.

It comes with many different predefined BlockingQueue configurations:

* Bounded LinkedBlockingQueue
* Unbounded LinkedBlockingQueue
* Bounded ArrayBlockingQueue
* Unbounded ArrayBlockingQueue
* SynchronousQueue

You can also set the rejection policy that should be used, e.g. what should be done if the dispatcher (e.g. the Actor) can't keep up 
and the mailbox is growing up to the limit defined. You can choose between four different rejection policies:

* java.util.concurrent.ThreadPoolExecutor.CallerRuns - will run the message processing in the caller's thread as a way to slow him down and balance producer/consumer
* java.util.concurrent.ThreadPoolExecutor.AbortPolicy - rejected messages by throwing a ``RejectedExecutionException``
* java.util.concurrent.ThreadPoolExecutor.DiscardPolicy - discards the message (throws it away)
* java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy - discards the oldest message in the mailbox (throws it away)

You can read more about these policies `here <http://java.sun.com/javase/6/docs/api/index.html?java/util/concurrent/RejectedExecutionHandler.html>`_.

Here is an example of a bounded mailbox:

.. includecode:: code/DispatcherDocSpec.scala#my-bounded-config

The standard :class:`Dispatcher` allows you to define the ``throughput`` it
should have, as shown above. This defines the number of messages for a specific
Actor the dispatcher should process in one single sweep; in other words, the
dispatcher will bunch up to ``throughput`` messages together when
having elected an actor to run.  Setting this to a higher number will increase
throughput but lower fairness, and vice versa. If you don't specify it explicitly 
then it uses the value (5) defined for ``default-dispatcher`` in the :ref:`configuration`.

Browse the `ScalaDoc <scaladoc>`_ or look at the code for all the options available.

Priority event-based
^^^^^^^^^^^^^^^^^^^^

Sometimes it's useful to be able to specify priority order of messages, that is done by using Dispatcher and supply
an UnboundedPriorityMailbox or BoundedPriorityMailbox with a ``java.util.Comparator[Envelope]`` or use a 
``akka.dispatch.PriorityGenerator`` (recommended):

Creating a Dispatcher using PriorityGenerator:

.. includecode:: code/DispatcherDocSpec.scala#prio-dispatcher


Work-stealing event-based
^^^^^^^^^^^^^^^^^^^^^^^^^

The ``BalancingDispatcher`` is a variation of the ``Dispatcher`` in which Actors of the same type can be set up to 
share this dispatcher and during execution time the different actors will steal messages from other actors if they 
have less messages to process. This can be a great way to improve throughput at the cost of a little higher latency.

.. includecode:: code/DispatcherDocSpec.scala#my-balancing-config

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
        task-queue-size = 1000   # If negative (or zero) then an unbounded mailbox is used (default)
                                 # If positive then a bounded mailbox is used and the capacity is set to the number specified
      }
    }
  }

Per-instance based configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can also do it on a specific dispatcher instance.

.. includecode:: code/DispatcherDocSpec.scala#my-bounded-config


For the ``PinnedDispatcher``, it is non-shareable between actors, and associates a dedicated Thread with the actor.
Making it bounded (by specifying a capacity) is optional, but if you do, you need to provide a pushTimeout (default is 10 seconds). 
When trying to send a message to the Actor it will throw a MessageQueueAppendFailedException("BlockingMessageTransferQueue transfer timed out") 
if the message cannot be added to the mailbox within the time specified by the pushTimeout.

