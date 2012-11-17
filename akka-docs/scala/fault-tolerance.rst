.. _fault-tolerance-scala:

Fault Tolerance (Scala)
=======================

As explained in :ref:`actor-systems` each actor is the supervisor of its
children, and as such each actor defines fault handling supervisor strategy.
This strategy cannot be changed afterwards as it is an integral part of the
actor system’s structure.

Fault Handling in Practice
--------------------------

First, let us look at a sample that illustrates one way to handle data store errors,
which is a typical source of failure in real world applications. Of course it depends
on the actual application what is possible to do when the data store is unavailable,
but in this sample we use a best effort re-connect approach.

Read the following source code. The inlined comments explain the different pieces of
the fault handling and why they are added. It is also highly recommended to run this
sample as it is easy to follow the log output to understand what is happening in runtime.

.. toctree::

   fault-tolerance-sample

Creating a Supervisor Strategy
------------------------------

The following sections explain the fault handling mechanism and alternatives
in more depth.

For the sake of demonstration let us consider the following strategy:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: strategy

I have chosen a few well-known exception types in order to demonstrate the
application of the fault handling directives described in :ref:`supervision`.
First off, it is a one-for-one strategy, meaning that each child is treated
separately (an all-for-one strategy works very similarly, the only difference
is that any decision is applied to all children of the supervisor, not only the
failing one). There are limits set on the restart frequency, namely maximum 10
restarts per minute; each of these settings could be left out, which means
that the respective limit does not apply, leaving the possibility to specify an
absolute upper limit on the restarts or to make the restarts work infinitely.

The match statement which forms the bulk of the body is of type ``Decider``,
which is a ``PartialFunction[Throwable, Directive]``. This
is the piece which maps child failure types to their corresponding directives.

Default Supervisor Strategy
---------------------------

``Escalate`` is used if the defined strategy doesn't cover the exception that was thrown.

When the supervisor strategy is not defined for an actor the following
exceptions are handled by default:

* ``ActorInitializationException`` will stop the failing child actor
* ``ActorKilledException`` will stop the failing child actor
* ``Exception`` will restart the failing child actor
* Other types of ``Throwable`` will be escalated to parent actor

If the exception escalate all the way up to the root guardian it will handle it
in the same way as the default strategy defined above.


Test Application
----------------

The following section shows the effects of the different directives in practice,
wherefor a test setup is needed. First off, we need a suitable supervisor:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: supervisor

This supervisor will be used to create a child, with which we can experiment:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: child

The test is easier by using the utilities described in :ref:`akka-testkit`,
where ``AkkaSpec`` is a convenient mixture of ``TestKit with WordSpec with
MustMatchers``

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: testkit

Let us create actors:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: create

The first test shall demonstrate the ``Resume`` directive, so we try it out by
setting some non-initial state in the actor and have it fail:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: resume

As you can see the value 42 survives the fault handling directive. Now, if we
change the failure to a more serious ``NullPointerException``, that will no
longer be the case:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: restart

And finally in case of the fatal ``IllegalArgumentException`` the child will be
terminated by the supervisor:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: stop

Up to now the supervisor was completely unaffected by the child’s failure,
because the directives set did handle it. In case of an ``Exception``, this is not
true anymore and the supervisor escalates the failure.

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: escalate-kill

The supervisor itself is supervised by the top-level actor provided by the
:class:`ActorSystem`, which has the default policy to restart in case of all
``Exception`` cases (with the notable exceptions of
``ActorInitializationException`` and ``ActorKilledException``). Since the
default directive in case of a restart is to kill all children, we expected our poor
child not to survive this failure.

In case this is not desired (which depends on the use case), we need to use a
different supervisor which overrides this behavior.

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: supervisor2

With this parent, the child survives the escalated restart, as demonstrated in
the last test:

.. includecode:: code/akka/docs/actor/FaultHandlingDocSpec.scala
   :include: escalate-restart

