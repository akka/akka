.. _mailboxes-java:

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
by having that actor implement the parameterized interface :class:`RequiresMessageQueue`. Here is
an example:

.. includecode:: code/docs/actor/MyBoundedUntypedActor.java#my-bounded-untyped-actor

The type parameter to the :class:`RequiresMessageQueue` interface needs to be mapped to a mailbox in
configuration like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala
   :include: bounded-mailbox-config,required-mailbox-config

Now every time you create an actor of type :class:`MyBoundedUntypedActor` it will try to get a bounded
mailbox. If the actor has a different mailbox configured in deployment, either directly or via
a dispatcher with a specified mailbox type, then that will override this mapping.

.. note::

  The type of the queue in the mailbox created for an actor will be checked against the required type in the
  interface and if the queue doesn't implement the required type then actor creation will fail.

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

Builtin Mailbox Implementations
===============================

Akka comes shipped with a number of mailbox implementations:

* **UnboundedMailbox** (default)

  - The default mailbox

  - Backed by a ``java.util.concurrent.ConcurrentLinkedQueue``

  - Blocking: No

  - Bounded: No

  - Configuration name: ``"unbounded"`` or ``"akka.dispatch.UnboundedMailbox"``

* **SingleConsumerOnlyUnboundedMailbox**

  This queue may or may not be faster than the default one depending on your use-case—be sure to benchmark properly!

  - Backed by a Multiple-Producer Single-Consumer queue, cannot be used with ``BalancingDispatcher``

  - Blocking: No

  - Bounded: No

  - Configuration name: ``"akka.dispatch.SingleConsumerOnlyUnboundedMailbox"``

* **NonBlockingBoundedMailbox**

  - Backed by a very efficient Multiple-Producer Single-Consumer queue

  - Blocking: No (discards overflowing messages into deadLetters)

  - Bounded: Yes

  - Configuration name: ``"akka.dispatch.NonBlockingBoundedMailbox"``

* **UnboundedControlAwareMailbox**

  - Delivers messages that extend ``akka.dispatch.ControlMessage`` with higher priority

  - Backed by two ``java.util.concurrent.ConcurrentLinkedQueue``

  - Blocking: No

  - Bounded: No

  - Configuration name: "akka.dispatch.UnboundedControlAwareMailbox"
  
* **UnboundedPriorityMailbox**

  - Backed by a ``java.util.concurrent.PriorityBlockingQueue``

  - Delivery order for messages of equal priority is undefined - contrast with the UnboundedStablePriorityMailbox

  - Blocking: No

  - Bounded: No

  - Configuration name: "akka.dispatch.UnboundedPriorityMailbox"
  
* **UnboundedStablePriorityMailbox**

  - Backed by a ``java.util.concurrent.PriorityBlockingQueue`` wrapped in an ``akka.util.PriorityQueueStabilizer``

  - FIFO order is preserved for messages of equal priority - contrast with the UnboundedPriorityMailbox

  - Blocking: No

  - Bounded: No

  - Configuration name: "akka.dispatch.UnboundedStablePriorityMailbox"

Other bounded mailbox implementations which will block the sender if the capacity is reached and
configured with non-zero ``mailbox-push-timeout-time``. 

.. note:: The following mailboxes should only be used with zero ``mailbox-push-timeout-time``.

* **BoundedMailbox**

  - Backed by a ``java.util.concurrent.LinkedBlockingQueue``

  - Blocking: Yes if used with non-zero ``mailbox-push-timeout-time``, otherwise No

  - Bounded: Yes

  - Configuration name: "bounded" or "akka.dispatch.BoundedMailbox"

* **BoundedPriorityMailbox**

  - Backed by a ``java.util.PriorityQueue`` wrapped in an ``akka.util.BoundedBlockingQueue``

  - Delivery order for messages of equal priority is undefined - contrast with the ``BoundedStablePriorityMailbox``

  - Blocking: Yes if used with non-zero ``mailbox-push-timeout-time``, otherwise No

  - Bounded: Yes

  - Configuration name: ``"akka.dispatch.BoundedPriorityMailbox"``

* **BoundedStablePriorityMailbox**

  - Backed by a ``java.util.PriorityQueue`` wrapped in an ``akka.util.PriorityQueueStabilizer`` and an ``akka.util.BoundedBlockingQueue``

  - FIFO order is preserved for messages of equal priority - contrast with the BoundedPriorityMailbox

  - Blocking: Yes if used with non-zero ``mailbox-push-timeout-time``, otherwise No

  - Bounded: Yes

  - Configuration name: "akka.dispatch.BoundedStablePriorityMailbox"

* **BoundedControlAwareMailbox**

  - Delivers messages that extend ``akka.dispatch.ControlMessage`` with higher priority

  - Backed by two ``java.util.concurrent.ConcurrentLinkedQueue`` and blocking on enqueue if capacity has been reached

  - Blocking: Yes if used with non-zero ``mailbox-push-timeout-time``, otherwise No

  - Bounded: Yes

  - Configuration name: "akka.dispatch.BoundedControlAwareMailbox"

Mailbox configuration examples
==============================

PriorityMailbox
---------------

How to create a PriorityMailbox:

.. includecode:: ../java/code/docs/dispatcher/DispatcherDocTest.java#prio-mailbox

And then add it to the configuration:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#prio-dispatcher-config

And then an example on how you would use it:

.. includecode:: ../java/code/docs/dispatcher/DispatcherDocTest.java#prio-dispatcher

It is also possible to configure a mailbox type directly like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala
   :include: prio-mailbox-config-java,mailbox-deployment-config

And then use it either from deployment like this:

.. includecode:: code/docs/dispatcher/DispatcherDocTest.java#defining-mailbox-in-config

Or code like this:

.. includecode:: code/docs/dispatcher/DispatcherDocTest.java#defining-mailbox-in-code

ControlAwareMailbox
-------------------

A ``ControlAwareMailbox`` can be very useful if an actor needs to be able to receive control messages
immediately no matter how many other messages are already in its mailbox.

It can be configured like this:

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#control-aware-mailbox-config

Control messages need to extend the ``ControlMessage`` trait:

.. includecode:: ../java/code/docs/dispatcher/DispatcherDocTest.java#control-aware-mailbox-messages

And then an example on how you would use it:

.. includecode:: ../java/code/docs/dispatcher/DispatcherDocTest.java#control-aware-dispatcher

Creating your own Mailbox type
==============================

An example is worth a thousand quacks:

.. includecode:: code/docs/dispatcher/MyUnboundedJMailbox.java#mailbox-implementation-example

.. includecode:: code/docs/dispatcher/MyUnboundedJMessageQueueSemantics.java#mailbox-implementation-example

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

.. includecode:: ../scala/code/docs/dispatcher/DispatcherDocSpec.scala#custom-mailbox-config-java


Or by defining the requirement on your actor class like this:

.. includecode:: code/docs/dispatcher/DispatcherDocTest.java#require-mailbox-on-actor


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

   final Props props = ...
   // this actor uses MyCustomMailbox, which is assumed to be a singleton
   system.actorOf(props.withDispatcher("myCustomMailbox").tell("bang", sender);
   assert(MyCustomMailbox.getInstance().getLastEnqueued().equals("bang"));

will probably fail; you will have to allow for some time to pass and retry the
check à la :meth:`TestKit.awaitCond`.

