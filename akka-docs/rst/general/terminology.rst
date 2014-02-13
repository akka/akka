.. _terminology:

Terminology, Concepts
=====================

In this chapter we attempt to establish a common terminology to define a solid ground for communicating about concurrent,
distributed systems which Akka targets. Please note that, for many of these terms, there is no single agreed definition.
We simply seek to give working definitions that will be used in the scope of the Akka documentation.

Concurrency vs. Parallelism
---------------------------

Concurrency and parallelism are related concepts, but there are small differences. *Concurrency* means that two or more
tasks are making progress even though they might not be executing simultaneously. This can for example be realized with
time slicing where parts of tasks are executed sequentially and mixed with
parts of other tasks. *Parallelism* on the other hand arise when the execution can be truly simultaneous.

Asynchronous vs. Synchronous
----------------------------

A method call is considered *synchronous* if the caller cannot make progress until the method returns a value or throws
an exception. On the other hand, an *asynchronous* call allows the caller to progress after a finite number of steps, and
the completion of the method may be signalled via some additional mechanism (it might be a registered callback, a Future,
or a message).

A synchronous API may use blocking to implement synchrony, but this is not a necessity. A very CPU intensive task
might give a similar behavior as blocking. In general, it is preferred to use asynchronous APIs, as they guarantee that
the system is able to progress. Actors are asynchronous by nature: an actor can progress after a message send without
waiting for the actual delivery to happen.

Non-blocking vs. Blocking
-------------------------

We talk about *blocking* if the delay of one thread can indefinitely delay some of the other threads. A good example
is a resource which can be used exclusively by one thread using mutual exclusion. If a thread holds on to the resource
indefinitely (for example accidentally running an infinite loop) other threads waiting on the resource can not progress.
In contrast, *non-blocking* means that no thread is able to indefinitely delay others.

Non-blocking operations are preferred to blocking ones, as the overall progress of the system is not trivially guaranteed
when it contains blocking operations.

Deadlock vs. Starvation vs. Live-lock
-------------------------------------

*Deadlock* arises when several participants are waiting on each other to reach a specific state to be able to progress.
As none of them can progress without some other participant to reach a certain state (a "Catch-22" problem) all affected
subsystems stall. Deadlock is closely related to *blocking*, as it is necessary that a participant thread be able to
delay the progression of other threads indefinitely.

In the case of *deadlock*, no participants can make progress, while in contrast *Starvation* happens, when there are
participants that can make progress, but there might be one or more that cannot. Typical scenario is the case of a naive
scheduling algorithm that always selects high-priority tasks over low-priority ones. If the number of incoming
high-priority tasks is constantly high enough, no low-priority ones will be ever finished.

*Livelock* is similar to *deadlock* as none of the participants make progress. The difference though is that instead of
being frozen in a state of waiting for others to progress, the participants continuously change their state. An example
scenario when two participants have two identical resources available. They each try to get the resource, but they also
check if the other needs the resource, too. If the resource is requested by the other participant, they try to get
the other instance of the resource. In the unfortunate case it might happen that the two participants "bounce" between
the two resources, never acquiring it, but always yielding to the other.

Race Condition
--------------

We call it a *Race condition* when an assumption about the ordering of a set of events might be violated by external
non-deterministic effects. Race conditions often arise when multiple threads have a shared mutable state, and the
operations of thread on the state might be interleaved causing unexpected behavior. While this is a common case, shared
state is not necessary to have race conditions. One example could be a client sending unordered packets (e.g UDP
datagrams) ``P1``, ``P2`` to a server. As the packets might potentially travel via different network routes, it is possible that
the server receives ``P2`` first and ``P1`` afterwards. If the messages contain no information about their sending order it is
impossible to determine by the server that they were sent in a different order. Depending on the meaning of the packets
this can cause race conditions.

.. note::
  The only guarantee that Akka provides about messages sent between a given pair of actors is that their order is
  always preserved. see :ref:`message-delivery-reliability`

Non-blocking Guarantees (Progress Conditions)
---------------------------------------------

As discussed in the previous sections blocking is undesirable for several reasons, including the dangers of deadlocks
and reduced throughput in the system. In the following sections we discuss various non-blocking properties with
different strength.

Wait-freedom
............

A method is *wait-free* if every call is guaranteed to finish in a finite number of steps. If a method is
*bounded wait-free* then the number of steps has a finite upper bound.

From this definition it follows that wait-free methods are never blocking, therefore deadlock can not happen.
Additionally, as each participant can progress after a finite number of steps (when the call finishes), wait-free
methods are free of starvation.

Lock-freedom
............

*Lock-freedom* is a weaker property than *wait-freedom*. In the case of lock-free calls, infinitely often some method
finishes in a finite number of steps. This definition implies that no deadlock is possible for lock-free calls. On the
other hand, the guarantee that *some call finishes* in a finite number of steps is not enough to guarantee that
*all of them eventually finish*. In other words, lock-freedom is not enough to guarantee the lack of starvation.

Obstruction-freedom
...................

*Obstruction-freedom* is the weakest non-blocking guarantee discussed here. A method is called *obstruction-free* if
there is a point in time after which it executes in isolation (other threads make no steps, e.g.: become suspended), it
finishes in a bounded number of steps. All lock-free objects are obstruction-free, but the opposite is generally not
true.

*Optimistic concurrency control* (OCC) methods are usually obstruction-free. The OCC approach is that every participant
tries to execute its operation on the shared object, but if a participant detects conflicts from others, it rolls back
the modifications, and tries again according to some schedule. If there is a point in time, where one of the participants
is the only one trying, the operation will succeed.

Recommended literature
----------------------

 * The Art of Multiprocessor Programming, M. Herlihy and N Shavit, 2008. ISBN 978-0123705914
 * Java Concurrency in Practice, B. Goetz, T. Peierls, J. Bloch, J. Bowbeer, D. Holmes and D. Lea, 2006. ISBN 978-0321349606

