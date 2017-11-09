# Scheduler

Sometimes the need for making things happen in the future arises, and where do
you go look then?  Look no further than `ActorSystem`! There you find the
`scheduler` method that returns an instance of
`akka.actor.Scheduler`, this instance is unique per ActorSystem and is
used internally for scheduling things to happen at specific points in time.

You can schedule sending of messages to actors and execution of tasks
(functions or Runnable).  You will get a `Cancellable` back that you can call
`cancel` on to cancel the execution of the scheduled operation.

When scheduling periodic or single messages in an actor to itself it is recommended to
use the @ref:[Actor Timers](actors.md#actors-timers) instead of using the `Scheduler`
directly.

The scheduler in Akka is designed for high-throughput of thousands up to millions 
of triggers. The prime use-case being triggering Actor receive timeouts, Future timeouts,
circuit breakers and other time dependent events which happen all-the-time and in many 
instances at the same time. The implementation is based on a Hashed Wheel Timer, which is
a known datastructure and algorithm for handling such use cases, refer to the [Hashed and Hierarchical Timing Wheels](http://www.cs.columbia.edu/~nahum/w6998/papers/sosp87-timing-wheels.pdf) 
whitepaper by Varghese and Lauck if you'd like to understand its inner workings. 

The Akka scheduler is **not** designed for long-term scheduling (see [akka-quartz-scheduler](https://github.com/enragedginger/akka-quartz-scheduler) 
instead for this use case) nor is it to be used for higly precise firing of the events.
The maximum amount of time into the future you can schedule an event to trigger is around 8 months,
which in practice is too much to be useful since this would assume the system never went down during that period.
If you need long-term scheduling we highly recommend looking into alternative schedulers, as this
is not the use-case the Akka scheduler is implemented for.

@@@ warning

The default implementation of `Scheduler` used by Akka is based on job
buckets which are emptied according to a fixed schedule.  It does not
execute tasks at the exact time, but on every tick, it will run everything
that is (over)due.  The accuracy of the default Scheduler can be modified
by the `akka.scheduler.tick-duration` configuration property.

@@@

## Some examples

Scala
:  @@snip [SchedulerDocSpec.scala]($code$/scala/docs/actor/SchedulerDocSpec.scala) { #imports1 }

Java
:  @@snip [SchedulerDocTest.java]($code$/java/jdocs/actor/SchedulerDocTest.java) { #imports1 }

Schedule to send the "foo"-message to the testActor after 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala]($code$/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-one-off-message } 

Java
:  @@snip [SchedulerDocTest.java]($code$/java/jdocs/actor/SchedulerDocTest.java) { #schedule-one-off-message }

Schedule a @scala[function]@java[`Runnable`], that sends the current time to the testActor, to be executed after 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala]($code$/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-one-off-thunk }

Java
:  @@snip [SchedulerDocTest.java]($code$/java/jdocs/actor/SchedulerDocTest.java) { #schedule-one-off-thunk }

Schedule to send the "Tick"-message to the `tickActor` after 0ms repeating every 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala]($code$/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-recurring }

Java
:  @@snip [SchedulerDocTest.java]($code$/java/jdocs/actor/SchedulerDocTest.java) { #schedule-recurring }

@@@ warning

If you schedule functions or Runnable instances you should be extra careful
to not close over unstable references. In practice this means not using `this`
inside the closure in the scope of an Actor instance, not accessing `sender()` directly
and not calling the methods of the Actor instance directly. If you need to
schedule an invocation schedule a message to `self` instead (containing the
necessary parameters) and then call the method when the message is received.

@@@

## From `akka.actor.ActorSystem`

@@snip [ActorSystem.scala]($akka$/akka-actor/src/main/scala/akka/actor/ActorSystem.scala) { #scheduler }

@@@ warning

All scheduled task will be executed when the `ActorSystem` is terminated, i.e.
the task may execute before its timeout.

@@@

## The Scheduler interface

The actual scheduler implementation is loaded reflectively upon
`ActorSystem` start-up, which means that it is possible to provide a
different one using the `akka.scheduler.implementation` configuration
property. The referenced class must implement the following interface:

Scala
:  @@snip [Scheduler.scala]($akka$/akka-actor/src/main/scala/akka/actor/Scheduler.scala) { #scheduler }

Java
:  @@snip [AbstractScheduler.java]($akka$/akka-actor/src/main/java/akka/actor/AbstractScheduler.java) { #scheduler }

## The Cancellable interface

Scheduling a task will result in a `Cancellable` (or throw an
`IllegalStateException` if attempted after the scheduler’s shutdown).
This allows you to cancel something that has been scheduled for execution.

@@@ warning

This does not abort the execution of the task, if it had already been
started.  Check the return value of `cancel` to detect whether the
scheduled task was canceled or will (eventually) have run.

@@@

@@snip [Scheduler.scala]($akka$/akka-actor/src/main/scala/akka/actor/Scheduler.scala) { #cancellable }