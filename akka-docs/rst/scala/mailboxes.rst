.. _mailboxes-scala:

Mailboxes
#########

An Akka ``Mailbox`` holds the messages that are destined for an ``Actor``.
Normally each ``Actor`` has its own mailbox, but with for example a ``BalancingPool``
all routees will share a single mailbox instance.

Mailbox Selection
=================

Requiring a Message Queue Type for an Actor
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

Requiring a Message Queue Type for a Dispatcher
-----------------------------------------------

A dispatcher may also have a requirement for the mailbox type used by the
actors running on it. An example is the BalancingDispatcher which requires a
message queue that is thread-safe for multiple concurrent consumers. Such a
requirement is formulated within the dispatcher configuration section like
this::

  my-dispatcher {
    mailbox-requirement = org.example.MyInterface
  }

The given requirement names a class or interface which will then be ensured to
be a supertype of the message queue’s implementation. In case of a
conflict—e.g. if the actor requires a mailbox type which does not satisfy this
requirement—then actor creation will fail.

How the Mailbox Type is Selected
--------------------------------

When an actor is created, the :class:`ActorRefProvider` first determines the
dispatcher which will execute it. Then the mailbox is determined as follows:

1. If the actor’s deployment configuration section contains a ``mailbox`` key
   then that names a configuration section describing the mailbox type to be
   used.

2. If the actor’s ``Props`` contains a mailbox selection—i.e. ``withMailbox``
   was called on it—then that names a configuration section describing the
   mailbox type to be used.

3. If the dispatcher’s configuration section contains a ``mailbox-type`` key
   the same section will be used to configure the mailbox type.

4. If the actor requires a mailbox type as described above then the mapping for
   that requirement will be used to determine the mailbox type to be used; if
   that fails then the dispatcher’s requirement—if any—will be tried instead.

5. If the dispatcher requires a mailbox type as described above then the
   mapping for that requirement will be used to determine the mailbox type to
   be used.

6. The default mailbox ``akka.actor.default-mailbox`` will be used.

Default Mailbox
---------------

When the mailbox is not specified as described above the default mailbox 
is used. By default it is an unbounded mailbox, which is backed by a 
``java.util.concurrent.ConcurrentLinkedQueue``.

``SingleConsumerOnlyUnboundedMailbox`` is an even more efficient mailbox, and
it can be used as the default mailbox, but it cannot be used with a BalancingDispatcher.

Configuration of ``SingleConsumerOnlyUnboundedMailbox`` as default mailbox::

  akka.actor.default-mailbox {
    mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"
  }

Which Configuration is passed to the Mailbox Type
-------------------------------------------------

Each mailbox type is implemented by a class which extends :class:`MailboxType`
and takes two constructor arguments: a :class:`ActorSystem.Settings` object and
a :class:`Config` section. The latter is computed by obtaining the named
configuration section from the actor system’s configuration, overriding its
``id`` key with the configuration path of the mailbox type and adding a
fall-back to the default mailbox configuration section.

Builtin implementations
=======================

Akka comes shipped with a number of mailbox implementations:

* UnboundedMailbox
  - The default mailbox

  - Backed by a ``java.util.concurrent.ConcurrentLinkedQueue``

  - Blocking: No

  - Bounded: No

  - Configuration name: "unbounded" or "akka.dispatch.UnboundedMailbox"

* SingleConsumerOnlyUnboundedMailbox

  - Backed by a very efficient Multiple Producer Single Consumer queue, cannot be used with BalancingDispatcher

  - Blocking: No

  - Bounded: No

  - Configuration name: "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"

* BoundedMailbox

  - Backed by a ``java.util.concurrent.LinkedBlockingQueue``

  - Blocking: Yes

  - Bounded: Yes

  - Configuration name: "bounded" or "akka.dispatch.BoundedMailbox"

* UnboundedPriorityMailbox

  - Backed by a ``java.util.concurrent.PriorityBlockingQueue``

  - Blocking: Yes

  - Bounded: No

  - Configuration name: "akka.dispatch.UnboundedPriorityMailbox"

* BoundedPriorityMailbox

  - Backed by a ``java.util.PriorityBlockingQueue`` wrapped in an ``akka.util.BoundedBlockingQueue``

  - Blocking: Yes

  - Bounded: Yes

  - Configuration name: "akka.dispatch.BoundedPriorityMailbox"

Mailbox configuration examples
==============================

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


Creating your own Mailbox type
==============================

An example is worth a thousand quacks:

.. includecode:: ../scala/code/docs/dispatcher/MyUnboundedMailbox.scala#mailbox-implementation-example

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

You can also use the mailbox as a requirement on the dispatcher like this:

.. includecode:: code/docs/dispatcher/DispatcherDocSpec.scala#custom-mailbox-config-java


Or by defining the requirement on your actor class like this:

.. includecode:: code/docs/dispatcher/DispatcherDocSpec.scala#require-mailbox-on-actor


Special Semantics of ``system.actorOf``
=======================================

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
