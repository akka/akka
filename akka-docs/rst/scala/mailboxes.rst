.. _mailboxes-scala:

Mailboxes
=========

An Akka ``Mailbox`` holds the messages that are destined for an ``Actor``.
Normally each ``Actor`` has its own mailbox, but with for example a ``BalancingDispatcher``
all actors with the same ``BalancingDispatcher`` will share a single instance.

Builtin implementations
-----------------------

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
------------------------------

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
-------------------------------------------

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
  trait and if the queue doesn't implement the required type then actor creation will fail.


Mailbox configuration precedence
--------------------------------

The order of precedence for the mailbox type of an actor, where lower numbers override higher, is:

1. Mailbox type configured in the deployment of the actor
2. Mailbox type configured on the dispatcher of the actor
3. Mailbox type configured on the Props of the actor
4. Mailbox type configured via message queue requirement


Creating your own Mailbox type
------------------------------

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
---------------------------------------

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
