.. _dispatchers-scala:

Dispatchers
===================

An Akka ``MessageDispatcher`` is what makes Akka Actors "tick", it is the engine of the machine so to speak.
All ``MessageDispatcher`` implementations are also an ``ExecutionContext``, which means that they can be used
to execute arbitrary code, for instance :ref:`futures-scala`.

Default dispatcher
------------------

Every ``ActorSystem`` will have a default dispatcher that will be used in case nothing else is configured for an ``Actor``.
The default dispatcher can be configured, and is by default a ``Dispatcher`` with a "fork-join-executor", which gives excellent performance in most cases.

.. _dispatcher-lookup-scala:

Looking up a Dispatcher
-----------------------

Dispatchers implement the :class:`ExecutionContext` interface and can thus be used to run :class:`Future` invocations etc.

.. includecode:: code/docs/dispatcher/DispatcherDocSpec.scala#lookup

Setting the dispatcher for an Actor
-----------------------------------

So in case you want to give your ``Actor`` a different dispatcher than the default, you need to do two things, of which the first 
is to configure the dispatcher:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#my-dispatcher-config

And here's another example that uses the "thread-pool-executor":

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#my-thread-pool-dispatcher-config

For more options, see the default-dispatcher section of the :ref:`configuration`.

Then you create the actor as usual and define the dispatcher in the deployment configuration.

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#defining-dispatcher-in-config

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#dispatcher-deployment-config

An alternative to the deployment configuration is to define the dispatcher in code.
If you define the ``dispatcher`` in the deployment configuration then this value will be used instead
of programmatically provided parameter.

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#defining-dispatcher-in-code

.. note::
    The dispatcher you specify in ``withDispatcher`` and the ``dispatcher`` property in the deployment 
    configuration is in fact a path into your configuration.
    So in this example it's a top-level section, but you could for instance put it as a sub-section,
    where you'd use periods to denote sub-sections, like this: ``"foo.bar.my-dispatcher"``

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

  - This dispatcher dedicates a unique thread for each actor using it; i.e. each actor will have its own thread pool with only one thread in the pool.

  - Sharability: None

  - Mailboxes: Any, creates one per Actor

  - Use cases: Bulkheading

  - Driven by: Any ``akka.dispatch.ThreadPoolExecutorConfigurator``
               by default a "thread-pool-executor"

* BalancingDispatcher

  - This is an executor based event driven dispatcher that will try to redistribute work from busy actors to idle actors.

  - All the actors share a single Mailbox that they get their messages from.

  - It is assumed that all actors using the same instance of this dispatcher can process all messages that have been sent to one of the actors; i.e. the actors belong to a pool of actors, and to the client there is no guarantee about which actor instance actually processes a given message.

  - Sharability: Actors of the same type only

  - Mailboxes: Any, creates one for all Actors

  - Use cases: Work-sharing

  - Driven by: ``java.util.concurrent.ExecutorService``
               specify using "executor" using "fork-join-executor",
               "thread-pool-executor" or the FQCN of
               an ``akka.dispatcher.ExecutorServiceConfigurator``

  - Note that you can **not** use a ``BalancingDispatcher`` as a **Router Dispatcher**. (You can however use it for the **Routees**)

* CallingThreadDispatcher

  - This dispatcher runs invocations on the current thread only. This dispatcher does not create any new threads,
    but it can be used from different threads concurrently for the same actor. See :ref:`Scala-CallingThreadDispatcher`
    for details and restrictions.

  - Sharability: Unlimited

  - Mailboxes: Any, creates one per Actor per Thread (on demand)

  - Use cases: Testing

  - Driven by: The calling thread (duh)


More dispatcher configuration examples
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Configuring a ``PinnedDispatcher``:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#my-pinned-dispatcher-config

And then using it:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#defining-pinned-dispatcher

Note that ``thread-pool-executor`` configuration as per the above ``my-thread-pool-dispatcher`` example is
NOT applicable. This is because every actor will have its own thread pool when using ``PinnedDispatcher``,
and that pool will have only one thread.

Note that it's not guaranteed that the *same* thread is used over time, since the core pool timeout
is used for ``PinnedDispatcher`` to keep resource usage down in case of idle actors. To use the same
thread all the time you need to add ``thread-pool-executor.allow-core-timeout=off`` to the
configuration of the ``PinnedDispatcher``.

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

* Durable mailboxes, see :ref:`durable-mailboxes-scala`.

Mailbox configuration examples
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

How to create a PriorityMailbox:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#prio-mailbox

And then add it to the configuration:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#prio-dispatcher-config

And then an example on how you would use it:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#prio-dispatcher

It is also possible to configure a mailbox type directly like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala
   :include: prio-mailbox-config,mailbox-deployment-config

And then use it either from deployment like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#defining-mailbox-in-config

Or code like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#defining-mailbox-in-code


Requiring a message queue type for an Actor
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

It is possible to require a certain type of message queue for a certain type of actor
by having that actor extend the parameterized trait :class:`RequiresMessageQueue`. Here is
an example:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#required-mailbox-class

The type parameter to the :class:`RequiresMessageQueue` trait needs to be mapped to a mailbox in
configuration like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala
   :include: bounded-mailbox-config,required-mailbox-config

Now every time you create an actor of type :class:`MyBoundedActor` it will try to get a bounded
mailbox. If the actor has a different mailbox configured in deployment, either directly or via
a dispatcher with a specified mailbox type, then that will override this mapping.

.. note::

  The type of the queue in the mailbox created for an actor will be checked against the required type in the
  trait and if the queue doesn't implement the required type an error will be logged.


Mailbox configuration precedence
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The order of precedence for the mailbox type of an actor, where lower numbers override higher, is:

1. Mailbox type configured in the deployment of the actor
2. Mailbox type configured on the dispatcher of the actor
3. Mailbox type configured on the Props of the actor
4. Mailbox type configured via message queue requirement


Creating your own Mailbox type
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

An example is worth a thousand quacks:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#mailbox-implementation-example

And then you just specify the FQCN of your MailboxType as the value of the "mailbox-type" in the dispatcher
configuration, or the mailbox configuration.

.. note::

  Make sure to include a constructor which takes
  ``akka.actor.ActorSystem.Settings`` and ``com.typesafe.config.Config``
  arguments, as this constructor is invoked reflectively to construct your
  mailbox type. The config passed in as second argument is that section from
  the configuration which describes the dispatcher or mailbox setting using
  this mailbox type; the mailbox type will be instantiated once for each
  dispatcher or mailbox setting using it.


Special Semantics of ``system.actorOf``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In order to make ``system.actorOf`` both synchronous and non-blocking while
keeping the return type :class:`ActorRef` (and the semantics that the returned
ref is fully functional), special handling takes place for this case. Behind
the scenes, a hollow kind of actor reference is constructed, which is sent to
the system’s guardian actor who actually creates the actor and its context and
puts those inside the reference. Until that has happened, messages sent to the
:class:`ActorRef` will be queued locally, and only upon swapping the real
filling in will they be transferred into the real mailbox. Thus,

.. code-block:: scala

   val props: Props = ...
   // this actor uses MyCustomMailbox, which is assumed to be a singleton
   system.actorOf(props.withDispatcher("myCustomMailbox")) ! "bang"
   assert(MyCustomMailbox.instance.getLastEnqueuedMessage == "bang")

will probably fail; you will have to allow for some time to pass and retry the
check à la :meth:`TestKit.awaitCond`.

