
.. _scheduler-java:

##################
 Scheduler (Java)
##################

Sometimes the need for making things happen in the future arises, and where do you go look then?
Look no further than ``ActorSystem``! There you find the :meth:`scheduler` method that returns an instance
of akka.actor.Scheduler, this instance is unique per ActorSystem and is used internally for scheduling things
to happen at specific points in time. Please note that the scheduled tasks are executed by the default
``MessageDispatcher`` of the ``ActorSystem``.

You can schedule sending of messages to actors and execution of tasks (functions or Runnable).
You will get a ``Cancellable`` back that you can call :meth:`cancel` on to cancel the execution of the
scheduled operation.

.. warning::

    The default implementation of ``Scheduler`` used by Akka is based on the Netty ``HashedWheelTimer``.
    It does not execute tasks at the exact time, but on every tick, it will run everything that is overdue.
    The accuracy of the default Scheduler can be modified by the "ticks-per-wheel" and "tick-duration" configuration
    properties. For more information, see: `HashedWheelTimers <http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt>`_.

Some examples
-------------

Schedule to send the "foo"-message to the testActor after 50ms:

.. includecode:: code/docs/actor/SchedulerDocTestBase.java
   :include: imports1

.. includecode:: code/docs/actor/SchedulerDocTestBase.java
   :include: schedule-one-off-message

Schedule a Runnable, that sends the current time to the testActor, to be executed after 50ms:

.. includecode:: code/docs/actor/SchedulerDocTestBase.java
   :include: schedule-one-off-thunk

Schedule to send the "Tick"-message to the ``tickActor`` after 0ms repeating every 50ms:

.. includecode:: code/docs/actor/SchedulerDocTestBase.java
   :include: imports1,imports2

.. includecode:: code/docs/actor/SchedulerDocTestBase.java
   :include: schedule-recurring

From ``akka.actor.ActorSystem``
-------------------------------

.. includecode:: ../../../akka-actor/src/main/scala/akka/actor/ActorSystem.scala
   :include: scheduler


The Scheduler interface
-----------------------

.. includecode:: ../../../akka-actor/src/main/scala/akka/actor/Scheduler.scala
   :include: scheduler

The Cancellable interface
-------------------------

This allows you to ``cancel`` something that has been scheduled for execution.

.. warning::
  This does not abort the execution of the task, if it had already been started.

.. includecode:: ../../../akka-actor/src/main/scala/akka/actor/Scheduler.scala
   :include: cancellable

