.. _dispatchers-scala:

Dispatchers (Scala)
===================

An Akka ``MessageDispatcher`` is what makes Akka Actors "tick", it is the engine of the machine so to speak.
All ``MessageDispatcher`` implementations are also an ``ExecutionContext``, which means that they can be used
to execute arbitrary code, for instance :ref:`futures-scala`.

Default dispatcher
------------------

Every ``ActorSystem`` will have a default dispatcher that will be used in case nothing else is configured for an ``Actor``.
The default dispatcher can be configured, and is by default a ``Dispatcher`` with a "fork-join-executor", which gives excellent performance in most cases.

Setting the dispatcher for an Actor
-----------------------------------

So in case you want to give your ``Actor`` a different dispatcher than the default, you need to do two things, of which the first is:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#defining-dispatcher

.. note::
    The "dispatcherId" you specify in withDispatcher is in fact a path into your configuration.
    So in this example it's a top-level section, but you could for instance put it as a sub-section,
    where you'd use periods to denote sub-sections, like this: ``"foo.bar.my-dispatcher"``

And then you just need to configure that dispatcher in your configuration:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-dispatcher-config

And here's another example that uses the "thread-pool-executor":

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-thread-pool-dispatcher-config

For more options, see the default-dispatcher section of the :ref:`configuration`.

Types of dispatchers
--------------------

There are 4 different types of message dispatchers:

* Dispatcher

  - This is an event-based dispatcher that binds a set of Actors to a thread pool. It is the default dispatcher
    used if one is not specified.

  - Sharability: Unlimited

  - Mailboxes: Any, creates one per Actor

  - Use cases: Default dispatcher, Bulkheading

  - Driven by: ``java.util.concurrent.ExecutorService``
               specify using "executor" using "fork-join-executor",
               "thread-pool-executor" or the FQCN of
               an ``akka.dispatcher.ExecutorServiceConfigurator``

* PinnedDispatcher

  - This dispatcher dedicates a unique thread for each actor passed in as reference.

  - Sharability: None

  - Mailboxes: Any, creates one per Actor

  - Use cases: Bulkheading

  - Driven by: Any ``akka.dispatch.ThreadPoolExecutorConfigurator``
               by default a "thread-pool-executor"

* BalancingDispatcher

  - This is an executor based event driven dispatcher that will try to redistribute work from busy actors to idle actors. 

  - It is assumed that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors; i.e. the actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.

  - Sharability: Actors of the same type only

  - Mailboxes: Any, creates one for all Actors

  - Use cases: Work-sharing

  - Driven by: ``java.util.concurrent.ExecutorService``
               specify using "executor" using "fork-join-executor",
               "thread-pool-executor" or the FQCN of
               an ``akka.dispatcher.ExecutorServiceConfigurator``

* CallingThreadDispatcher

  - This dispatcher runs invocations on the current thread only. This dispatcher does not create any new threads, 
    but it can be used from different threads concurrently for the same actor. 

  - Sharability: Unlimited

  - Mailboxes: Any, creates one per Actor per Thread (on demand)

  - Use cases: Testing

  - Driven by: The calling thread (duh)


More dispatcher configuration examples
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Configuring a ``PinnedDispatcher``:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#my-pinned-dispatcher-config

And then using it:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#defining-pinned-dispatcher

Note that ``thread-pool-executor`` configuration as per the above ``my-thread-pool-dispatcher`` exmaple is 
NOT applicable. This is because every actor will have its own thread pool when using ``PinnedDispatcher``, 
and that pool will have only one thread.

Mailboxes
---------

An Akka ``Mailbox`` holds the messages that are destined for an ``Actor``.
Normally each ``Actor`` has its own mailbox, but with example a ``BalancingDispatcher`` all actors with the same ``BalancingDispatcher`` will share a single instance.

Builtin implementations
^^^^^^^^^^^^^^^^^^^^^^^

Akka comes shipped with a number of default mailbox implementations:

* UnboundedMailbox

  - Backed by a ``java.util.concurrent.ConcurrentLinkedQueue``

  - Blocking: No

  - Bounded: No

* BoundedMailbox

  - Backed by a ``java.util.concurrent.LinkedBlockingQueue``

  - Blocking: Yes

  - Bounded: Yes

* UnboundedPriorityMailbox

  - Backed by a ``java.util.concurrent.PriorityBlockingQueue``

  - Blocking: Yes

  - Bounded: No

* BoundedPriorityMailbox

  - Backed by a ``java.util.PriorityBlockingQueue`` wrapped in an ``akka.util.BoundedBlockingQueue``

  - Blocking: Yes

  - Bounded: Yes

* Durable mailboxes, see :ref:`durable-mailboxes`.

Mailbox configuration examples
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

How to create a PriorityMailbox:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#prio-mailbox

And then add it to the configuration:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#prio-dispatcher-config

And then an example on how you would use it:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#prio-dispatcher

Creating your own Mailbox type
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

An example is worth a thousand quacks:

.. includecode:: ../scala/code/akka/docs/dispatcher/DispatcherDocSpec.scala#mailbox-implementation-example

And then you just specify the FQCN of your MailboxType as the value of the "mailbox-type" in the dispatcher configuration.

.. note::

  Make sure to include a constructor which takes
  ``akka.actor.ActorSystem.Settings`` and ``com.typesafe.config.Config``
  arguments, as this constructor is invoked reflectively to construct your
  mailbox type. The config passed in as second argument is that section from
  the configuration which describes the dispatcher using this mailbox type; the
  mailbox type will be instantiated once for each dispatcher using it.
