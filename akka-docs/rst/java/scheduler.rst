
.. _scheduler-java:

Scheduler
#########

Sometimes the need for making things happen in the future arises, and where do
you go look then?  Look no further than ``ActorSystem``! There you find the
:meth:`scheduler` method that returns an instance of
:class:`akka.actor.Scheduler`, this instance is unique per ActorSystem and is
used internally for scheduling things to happen at specific points in time.

You can schedule sending of messages to actors and execution of tasks
(functions or Runnable).  You will get a ``Cancellable`` back that you can call
:meth:`cancel` on to cancel the execution of the scheduled operation.

The scheduler in Akka is designed for high-throughput of thousands up to millions 
of triggers. The prime use-case being triggering Actor receive timeouts, Future timeouts,
circuit breakers and other time dependent events which happen all-the-time and in many 
instances at the same time. The implementation is based on a Hashed Wheel Timer, which is
a known datastructure and algorithm for handling such use cases, refer to the `Hashed and Hierarchical Timing Wheels`_ 
whitepaper by Varghese and Lauck if you'd like to understand its inner workings. 

The Akka scheduler is **not** designed for long-term scheduling (see `akka-quartz-scheduler`_ 
instead for this use case) nor is it to be used for higly precise firing of the events.
The maximum amount of time into the future you can schedule an event to trigger is around 8 months,
which in practice is too much to be useful since this would assume the system never went down during that period.
If you need long-term scheduling we highly recommend looking into alternative schedulers, as this
is not the use-case the Akka scheduler is implemented for.

.. warning::

    The default implementation of ``Scheduler`` used by Akka is based on job
    buckets which are emptied according to a fixed schedule.  It does not
    execute tasks at the exact time, but on every tick, it will run everything
    that is (over)due.  The accuracy of the default Scheduler can be modified
    by the ``akka.scheduler.tick-duration`` configuration property.

.. _akka-quartz-scheduler: https://github.com/enragedginger/akka-quartz-scheduler
.. _Hashed and Hierarchical Timing Wheels: http://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf

Some examples
-------------

Schedule to send the "foo"-message to the testActor after 50ms:

.. includecode:: code/jdocs/actor/SchedulerDocTest.java
   :include: imports1

.. includecode:: code/jdocs/actor/SchedulerDocTest.java
   :include: schedule-one-off-message

Schedule a Runnable, that sends the current time to the testActor, to be executed after 50ms:

.. includecode:: code/jdocs/actor/SchedulerDocTest.java
   :include: schedule-one-off-thunk

.. warning::

    If you schedule Runnable instances you should be extra careful
    to not pass or close over unstable references. In practice this means that you should
    not call methods on the enclosing Actor from within the Runnable.
    If you need to schedule an invocation it is better to use the ``schedule()``
    variant accepting a message and an ``ActorRef`` to schedule a message to self
    (containing the necessary parameters) and then call the method when the message is received.

Schedule to send the "Tick"-message to the ``tickActor`` after 0ms repeating every 50ms:

.. includecode:: code/jdocs/actor/SchedulerDocTest.java
   :include: imports1,imports2

.. includecode:: code/jdocs/actor/SchedulerDocTest.java
   :include: schedule-recurring

From ``akka.actor.ActorSystem``
-------------------------------

.. includecode:: ../../../akka-actor/src/main/scala/akka/actor/ActorSystem.scala
   :include: scheduler

.. warning::

  All scheduled task will be executed when the ``ActorSystem`` is terminated, i.e. 
  the task may execute before its timeout.

The Scheduler Interface for Implementors
----------------------------------------

The actual scheduler implementation is loaded reflectively upon
:class:`ActorSystem` start-up, which means that it is possible to provide a
different one using the ``akka.scheduler.implementation`` configuration
property. The referenced class must implement the following interface:

.. includecode:: ../../../akka-actor/src/main/java/akka/actor/AbstractScheduler.java
   :include: scheduler

The Cancellable interface
-------------------------

Scheduling a task will result in a :class:`Cancellable` (or throw an
:class:`IllegalStateException` if attempted after the scheduler’s shutdown).
This allows you to cancel something that has been scheduled for execution.

.. warning::

  This does not abort the execution of the task, if it had already been
  started.  Check the return value of ``cancel`` to detect whether the
  scheduled task was canceled or will (eventually) have run.

.. includecode:: ../../../akka-actor/src/main/scala/akka/actor/Scheduler.scala
   :include: cancellable

