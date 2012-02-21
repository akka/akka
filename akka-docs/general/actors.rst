.. _actors-general:

What is an Actor?
=================

The previous section about :ref:`actor-systems` explained how actors form
hierarchies and are the smallest unit when building an application. This
section looks at one such actor in isolation, explaining the concepts you
encounter while implementing it. For more an in depth reference with all the
details please refer to :ref:`actors-scala` and :ref:`untyped-actors-java`.

An actor is a container for `State`_, `Behavior`_, a `Mailbox`_, `Children`_
and a `Supervisor Strategy`_. All of this is encapsulated behind an `Actor
Reference`_. Finally, this happens `When an Actor Terminates`_.

Actor Reference
---------------

As detailed below, an actor object needs to be shielded from the outside in
order to benefit from the actor model. Therefore, actors are represented to the
outside using actor references, which are objects that can be passed around
freely and without restriction. This split into inner and outer object enables
transparency for all the desired operations: restarting an actor without
needing to update references elsewhere, placing the actual actor object on
remote hosts, sending messages to actors in completely different applications.
But the most important aspect is that it is not possible to look inside an
actor and get hold of its state from the outside, unless the actor unwisely
publishes this information itself.

State
-----

Actor objects will typically contain some variables which reflect possible
states the actor may be in. This can be an explicit state machine (e.g. using
the :ref:`fsm-scala` module), or it could be a counter, set of listeners,
pending requests, etc. These data are what make an actor valuable, and they
must be protected from corruption by other actors. The good news is that Akka
actors conceptually each have their own light-weight thread, which is
completely shielded from the rest of the system. This means that instead of
having to synchronize access using locks you can just write your actor code
without worrying about concurrency at all.

Behind the scenes Akka will run sets of actors on sets of real threads, where
typically many actors share one thread, and subsequent invocations of one actor
may end up being processed on different threads. Akka ensures that this
implementation detail does not affect the single-threadedness of handling the
actor’s state.

Because the internal state is vital to an actor’s operations, having
inconsistent state is fatal. Thus, when the actor fails and is restarted by its
supervisor, the state will be created from scratch, like upon first creating
the actor. This is to enable the ability of self-healing of the system.

Behavior
--------

Every time a message is processed, it is matched against the current behavior
of the actor. Behavior means a function which defines the actions to be taken
in reaction to the message at that point in time, say forward a request if the
client is authorized, deny it otherwise. This behavior may change over time,
e.g. because different clients obtain authorization over time, or because the
actor may go into an “out-of-service” mode and later come back. These changes
are achieved by either encoding them in state variables which are read from the
behavior logic, or the function itself may be swapped out at runtime, see the
``become`` and ``unbecome`` operations. However, the initial behavior defined
during construction of the actor object is special in the sense that a restart
of the actor will reset its behavior to this initial one.

.. note::
   The initial behavior of an Actor is extracted prior to constructor is run,
   so if you want to base your initial behavior on member state, you should
   use ``become`` in the constructor.

Mailbox
-------

An actor’s purpose is the processing of messages, and these messages were sent
to the actor from other actors (or from outside the actor system). The piece
which connects sender and receiver is the actor’s mailbox: each actor has
exactly one mailbox to which all senders enqueue their messages. Enqueuing
happens in the time-order of send operations, which means that messages sent
from different actors may not have a defined order at runtime due to the
apparent randomness of distributing actors across threads. Sending multiple
messages to the same target from the same actor, on the other hand, will
enqueue them in the same order.

There are different mailbox implementations to choose from, the default being a
FIFO: the order of the messages processed by the actor matches the order in
which they were enqueued. This is usually a good default, but applications may
need to prioritize some messages over others. In this case, a priority mailbox
will enqueue not always at the end but at a position as given by the message
priority, which might even be at the front. While using such a queue, the order
of messages processed will naturally be defined by the queue’s algorithm and in
general not be FIFO.

An important feature in which Akka differs from some other actor model
implementations is that the current behavior must always handle the next
dequeued message, there is no scanning the mailbox for the next matching one.
Failure to handle a message will typically be treated as a failure, unless this
behavior is overridden.

Children
--------

Each actor is potentially a supervisor: if it creates children for delegating
sub-tasks, it will automatically supervise them. The list of children is
maintained within the actor’s context and the actor has access to it.
Modifications to the list are done by creating (``context.actorOf(...)``) or
stopping (``context.stop(child)``) children and these actions are reflected
immediately. The actual creation and termination actions happen behind the
scenes in an asynchronous way, so they do not “block” their supervisor.

Supervisor Strategy
-------------------

The final piece of an actor is its strategy for handling faults of its
children. Fault handling is then done transparently by Akka, applying one
of the strategies described in :ref:`supervision` for each incoming failure.
As this strategy is fundamental to how an actor system is structured, it
cannot be changed once an actor has been created.

Considering that there is only one such strategy for each actor, this means
that if different strategies apply to the various children of an actor, the
children should be grouped beneath intermediate supervisors with matching
strategies, preferring once more the structuring of actor systems according to
the splitting of tasks into sub-tasks.

When an Actor Terminates
------------------------

Once an actor terminates, i.e. fails in a way which is not handled by a
restart, stops itself or is stopped by its supervisor, it will free up its
resources, draining all remaining messages from its mailbox into the system’s
“dead letter mailbox”. The mailbox is then replaced within the actor reference
with a that system mailbox, redirecting all new messages “into the drain”. This
is done on a best effort basis, though, so do not rely on it in order to
construct “guaranteed delivery”.

The reason for not just silently dumping the messages was inspired by our
tests: we register the TestEventListener on the event bus to which the dead
letters are forwarded, and that will log a warning for every dead letter
received—this has been very helpful for deciphering test failures more quickly.
It is conceivable that this feature may also be of use for other purposes.


