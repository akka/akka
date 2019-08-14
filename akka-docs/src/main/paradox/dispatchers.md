# Classic Dispatchers

@@@ note

Akka Classic is the original Actor APIs, which have been improved by more type safe and guided Actor APIs, 
known as Akka Typed. Akka Classic is still fully supported and existing applications can continue to use 
the classic APIs. It is also possible to use Akka Typed together with classic actors within the same 
ActorSystem, see @ref[coexistence](typed/coexisting.md). For new projects we recommend using the new Actor APIs.

For the new API see @ref[dispatchers](typed/dispatchers.md).

@@@

## Dependency

Dispatchers are part of core Akka, which means that they are part of the akka-actor dependency:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

An Akka `MessageDispatcher` is what makes Akka Actors "tick", it is the engine of the machine so to speak.
All `MessageDispatcher` implementations are also an `ExecutionContext`, which means that they can be used
to execute arbitrary code, for instance @ref:[Futures](futures.md).

For full details on how to work with dispatchers see the @ref:[main dispatcher docs](typed/dispatchers.md#types-of-dispatchers).

## Setting the dispatcher for an Actor

So in case you want to give your `Actor` a different dispatcher than the default, you need to do two things, of which the first
is to configure the dispatcher:

<!--same config text for Scala & Java-->
@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #my-dispatcher-config }

@@@ note

Note that the `parallelism-max` does not set the upper bound on the total number of threads
allocated by the ForkJoinPool. It is a setting specifically talking about the number of *hot*
threads the pool keep running in order to reduce the latency of handling a new incoming task.
You can read more about parallelism in the JDK's [ForkJoinPool documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html).

@@@

Another example that uses the "thread-pool-executor":

<!--same config text for Scala & Java-->
@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #fixed-pool-size-dispatcher-config }

@@@ note

The thread pool executor dispatcher is implemented using by a `java.util.concurrent.ThreadPoolExecutor`.
You can read more about it in the JDK's [ThreadPoolExecutor documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html).

@@@

For more options, see the default-dispatcher section of the @ref:[configuration](general/configuration.md).

Then you create the actor as usual and define the dispatcher in the deployment configuration.

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-dispatcher-in-config }

Java
:  @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-dispatcher-in-config }

<!--same config text for Scala & Java-->
@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #dispatcher-deployment-config } 

An alternative to the deployment configuration is to define the dispatcher in code.
If you define the `dispatcher` in the deployment configuration then this value will be used instead
of programmatically provided parameter.

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-dispatcher-in-code }

Java
:  @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-dispatcher-in-code }

@@@ note

The dispatcher you specify in `withDispatcher` and the `dispatcher` property in the deployment
configuration is in fact a path into your configuration.
So in this example it's a top-level section, but you could for instance put it as a sub-section,
where you'd use periods to denote sub-sections, like this: `"foo.bar.my-dispatcher"`

@@@

### More dispatcher configuration examples

Configuring a dispatcher with fixed thread pool size, e.g. for actors that perform blocking IO:

@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #fixed-pool-size-dispatcher-config }

And then using it:

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-fixed-pool-size-dispatcher }

Java
:  @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-fixed-pool-size-dispatcher }

Another example that uses the thread pool based on the number of cores (e.g. for CPU bound tasks)

<!--same config text for Scala & Java-->
@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) {#my-thread-pool-dispatcher-config }

A different kind of dispatcher that uses an affinity pool may increase throughput in cases where there is relatively small
number of actors that maintain some internal state. The affinity pool tries its best to ensure that an actor is always
scheduled to run on the same thread. This actor to thread pinning aims to decrease CPU cache misses which can result 
in significant throughput improvement.

@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #affinity-pool-dispatcher-config }

Configuring a `PinnedDispatcher`:

<!--same config text for Scala & Java-->
@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) {#my-pinned-dispatcher-config }

And then using it:

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-pinned-dispatcher }

Java
:  @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-pinned-dispatcher }

Note that `thread-pool-executor` configuration as per the above `my-thread-pool-dispatcher` example is
NOT applicable. This is because every actor will have its own thread pool when using `PinnedDispatcher`,
and that pool will have only one thread.

Note that it's not guaranteed that the *same* thread is used over time, since the core pool timeout
is used for `PinnedDispatcher` to keep resource usage down in case of idle actors. To use the same
thread all the time you need to add `thread-pool-executor.allow-core-timeout=off` to the
configuration of the `PinnedDispatcher`.
