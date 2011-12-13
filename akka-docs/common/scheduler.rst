Scheduler
=========

Sometimes the need for making things happen in the future arises, and where do you go look then?
Look no further than ``ActorSystem``! There you find the :meth:``scheduler`` method that returns an instance
of akka.actor.Scheduler, this instance is unique per ActorSystem and is used internally for scheduling things
to happen at specific points in time. Please note that the scheduled tasks are executed by the default
``MessageDispatcher`` of the ``ActorSystem``.

You can schedule sending of messages to actors and execution of tasks (functions or Runnable).
You will get a ``Cancellable`` back that you can call :meth:``cancel`` on to cancel the execution of the
scheduled operation.

Some examples
-------------

.. includecode:: code/SchedulerDocSpec.scala
   :include: imports1,schedule-one-off-message

.. includecode:: code/SchedulerDocSpec.scala
   :include: imports1,schedule-one-off-thunk

.. includecode:: code/SchedulerDocSpec.scala
   :include: imports1,schedule-one-off-runnable

.. includecode:: code/SchedulerDocSpec.scala
   :include: imports1,schedule-recurring

From ``akka.actor.ActorSystem``
-------------------------------

.. includecode:: ../../akka-actor/src/main/scala/akka/actor/ActorSystem.scala
   :include: scheduler


The Scheduler interface
-----------------------

.. includecode:: ../../akka-actor/src/main/scala/akka/actor/Scheduler.scala
   :include: scheduler

The Cancellable interface
-------------------------

This allows you to ``cancel`` something that has been scheduled for execution.

.. warning::
  This does not abort the execution of the task, if it had already been started.

.. includecode:: ../../akka-actor/src/main/scala/akka/actor/Scheduler.scala
   :include: cancellable

