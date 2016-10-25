<a id="scheduler-scala"></a>
# Scheduler

Sometimes the need for making things happen in the future arises, and where do
you go look then?  Look no further than `ActorSystem`! There you find the
`scheduler` method that returns an instance of
`akka.actor.Scheduler`, this instance is unique per ActorSystem and is
used internally for scheduling things to happen at specific points in time.

You can schedule sending of messages to actors and execution of tasks
(functions or Runnable).  You will get a `Cancellable` back that you can call
`cancel` on to cancel the execution of the scheduled operation.

> **Warning:**
The default implementation of `Scheduler` used by Akka is based on job
buckets which are emptied according to a fixed schedule.  It does not
execute tasks at the exact time, but on every tick, it will run everything
that is (over)due.  The accuracy of the default Scheduler can be modified
by the `akka.scheduler.tick-duration` configuration property.

## Some examples

@@snip [SchedulerDocSpec.scala](code/docs/actor/SchedulerDocSpec.scala) { #imports1 #schedule-one-off-message }

@@snip [SchedulerDocSpec.scala](code/docs/actor/SchedulerDocSpec.scala) { #schedule-one-off-thunk }

@@snip [SchedulerDocSpec.scala](code/docs/actor/SchedulerDocSpec.scala) { #schedule-recurring }

> **Warning:**
If you schedule functions or Runnable instances you should be extra careful
to not close over unstable references. In practice this means not using `this`
inside the closure in the scope of an Actor instance, not accessing `sender()` directly
and not calling the methods of the Actor instance directly. If you need to
schedule an invocation schedule a message to `self` instead (containing the
necessary parameters) and then call the method when the message is received.

## From `akka.actor.ActorSystem`

@@snip [ActorSystem.scala](../../../../../akka-actor/src/main/scala/akka/actor/ActorSystem.scala) { #scheduler }

> **Warning:**
All scheduled task will be executed when the `ActorSystem` is terminated, i.e.
the task may execute before its timeout.

## The Scheduler interface

The actual scheduler implementation is loaded reflectively upon
`ActorSystem` start-up, which means that it is possible to provide a
different one using the `akka.scheduler.implementation` configuration
property. The referenced class must implement the following interface:

@@snip [Scheduler.scala](../../../../../akka-actor/src/main/scala/akka/actor/Scheduler.scala) { #scheduler }

## The Cancellable interface

Scheduling a task will result in a `Cancellable` (or throw an
`IllegalStateException` if attempted after the scheduler�s shutdown).
This allows you to cancel something that has been scheduled for execution.

> **Warning:**
This does not abort the execution of the task, if it had already been
started.  Check the return value of `cancel` to detect whether the
scheduled task was canceled or will (eventually) have run.

@@snip [Scheduler.scala](../../../../../akka-actor/src/main/scala/akka/actor/Scheduler.scala) { #cancellable }