# Scheduler

## Dependency

To use Scheduler, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

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
instead for this use case) nor is it to be used for highly precise firing of the events.
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
:  @@snip [SchedulerDocSpec.scala](/akka-docs/src/test/scala/docs/actor/SchedulerDocSpec.scala) { #imports1 }

Java
:  @@snip [SchedulerDocTest.java](/akka-docs/src/test/java/jdocs/actor/SchedulerDocTest.java) { #imports1 }

Schedule to send the "foo"-message to the testActor after 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala](/akka-docs/src/test/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-one-off-message } 

Java
:  @@snip [SchedulerDocTest.java](/akka-docs/src/test/java/jdocs/actor/SchedulerDocTest.java) { #schedule-one-off-message }

Schedule a @scala[function]@java[`Runnable`], that sends the current time to the testActor, to be executed after 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala](/akka-docs/src/test/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-one-off-thunk }

Java
:  @@snip [SchedulerDocTest.java](/akka-docs/src/test/java/jdocs/actor/SchedulerDocTest.java) { #schedule-one-off-thunk }

Schedule to send the "Tick"-message to the `tickActor` after 0ms repeating every 50ms:

Scala
:  @@snip [SchedulerDocSpec.scala](/akka-docs/src/test/scala/docs/actor/SchedulerDocSpec.scala) { #schedule-recurring }

Java
:  @@snip [SchedulerDocTest.java](/akka-docs/src/test/java/jdocs/actor/SchedulerDocTest.java) { #schedule-recurring }

@@@ warning

If you schedule functions or Runnable instances you should be extra careful
to not close over unstable references. In practice this means not using `this`
inside the closure in the scope of an Actor instance, not accessing `sender()` directly
and not calling the methods of the Actor instance directly. If you need to
schedule an invocation schedule a message to `self` instead (containing the
necessary parameters) and then call the method when the message is received.

@@@

@@@ warning

All scheduled task will be executed when the `ActorSystem` is terminated, i.e.
the task may execute before its timeout.

@@@

## Schedule periodically

Scheduling of recurring tasks or messages can have two different characteristics:

* fixed-delay - The delay between subsequent execution will always be (at least) the given `delay`.
  Use `scheduleWithFixedDelay`.
* fixed-rate - The frequency of execution over time will meet the given `interval`. Use `scheduleAtFixedRate`.

If you are uncertain of which one to use you should pick `scheduleWithFixedDelay`.

When using **fixed-delay** it will not compensate the delay between tasks or messages if the execution takes long
time or if scheduling is delayed longer than specified for some reason. The delay between subsequent execution
will always be (at least) the given `delay`. In the long run, the frequency of execution will generally be
slightly lower than the reciprocal of the specified `delay`.

Fixed-delay execution is appropriate for recurring activities that require "smoothness." In other words,
it is appropriate for activities where it is more important to keep the frequency accurate in the short run
than in the long run.

When using **fixed-rate** it will compensate the delay for a subsequent task if the previous tasks took
too long to execute. For example, if the given `interval` is 1000 milliseconds and a task takes 200 milliseconds to
execute the next task will be scheduled to run after 800 milliseconds. In such cases, the actual execution
interval will differ from the interval passed to the `scheduleAtFixedRate` method.

If the execution of the tasks takes longer than the `interval`, the subsequent execution will start immediately
after the prior one completes (there will be no overlap of executions). This also has the consequence that after
long garbage collection pauses or other reasons when the JVM was suspended all "missed" tasks will execute
when the process wakes up again. For example, `scheduleAtFixedRate` with an interval of 1 second and the process
is suspended for 30 seconds will result in 30 tasks (or messages) being executed in rapid succession to catch up.
In the long run, the frequency of execution will be exactly the reciprocal of the specified `interval`.

Fixed-rate execution is appropriate for recurring activities that are sensitive to absolute time
or where the total time to perform a fixed number of executions is important, such as a countdown
timer that ticks once every second for ten seconds.

@@@ warning

`scheduleAtFixedRate` can result in bursts of scheduled tasks or messages after long garbage collection pauses,
which may in worst case cause undesired load on the system. `scheduleWithFixedDelay` is often preferred.

@@@


## The Scheduler interface

The actual scheduler implementation is loaded reflectively upon
`ActorSystem` start-up, which means that it is possible to provide a
different one using the `akka.scheduler.implementation` configuration
property. The referenced class must implement the @scala[@apidoc[akka.actor.Scheduler]]@java[@apidoc[akka.actor.AbstractScheduler]]
interface.

## The Cancellable interface

Scheduling a task will result in a @apidoc[akka.actor.Cancellable] (or throw an
`IllegalStateException` if attempted after the scheduler’s shutdown).
This allows you to cancel something that has been scheduled for execution.

@@@ warning

This does not abort the execution of the task, if it had already been
started.  Check the return value of `cancel` to detect whether the
scheduled task was canceled or will (eventually) have run.

@@@

