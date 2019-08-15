# Dispatchers

## Dependency

Dispatchers are part of core Akka, which means that they are part of the akka-actor-typed dependency:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor-typed_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction 

An Akka `MessageDispatcher` is what makes Akka Actors "tick", it is the engine of the machine so to speak.
All `MessageDispatcher` implementations are also an @scala[`ExecutionContext`]@java[`Executor`], which means that they can be used
to execute arbitrary code, for instance @ref:[Futures](../futures.md).

## Default dispatcher

Every `ActorSystem` will have a default dispatcher that will be used in case nothing else is configured for an `Actor`.
The default dispatcher can be configured, and is by default a `Dispatcher` with the specified `default-executor`.
If an ActorSystem is created with an ExecutionContext passed in, this ExecutionContext will be used as the default executor for all
dispatchers in this ActorSystem. If no ExecutionContext is given, it will fallback to the executor specified in
`akka.actor.default-dispatcher.default-executor.fallback`. By default this is a "fork-join-executor", which
gives excellent performance in most cases.

## Internal dispatcher

To protect the internal Actors that is spawned by the various Akka modules, a separate internal dispatcher is used by default.
The internal dispatcher can be tuned in a fine grained way with the setting `akka.actor.internal-dispatcher`, it can also
be replaced by another dispatcher by making `akka.actor.internal-dispatcher` an @ref[alias](#dispatcher-aliases).

<a id="dispatcher-lookup"></a>
## Looking up a Dispatcher

Dispatchers implement the `ExecutionContext` interface and can thus be used to run `Future` invocations etc.

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #lookup }

Java
:  @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #lookup }

## Selecting a dispatcher

A default dispatcher is used for all actors that are spawned without specifying a custom dispatcher.
This is suitable for all actors that don't block. Blocking in actors needs to be carefully managed, more
details @ref:[here](../dispatchers.md#blocking-needs-careful-management).

To select a dispatcher use `DispatcherSelector` to create a `Props` instance for spawning your actor:

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #spawn-dispatcher }

Java
:  @@snip [DispatcherDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/DispatchersDocTest.java) { #spawn-dispatcher }

`DispatcherSelector` has a few convenience methods:

* @scala[`DispatcherSelector.default`]@java[`DispatcherSelector.defaultDispatcher`] to look up the default dispatcher
* `DispatcherSelector.blocking` can be used to execute actors that block e.g. a legacy database API that does not support @scala[`Future`]@java[`CompletionStage`]s
* `DispatcherSelector.sameAsParent` to use the same dispatcher as the parent actor

The final example shows how to load a custom dispatcher from configuration and replies on this being in your application.conf:

Scala
:  @@snip [DispatcherDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #config }

Java
:  @@snip [DispatcherDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/DispatchersDocSpec.scala) { #config }

## Types of dispatchers

There are 3 different types of message dispatchers:

* **Dispatcher**

    This is an event-based dispatcher that binds a set of Actors to a thread
    pool. It is the default dispatcher used if one is not specified.

    * Sharability: Unlimited
    * Mailboxes: Any, creates one per Actor
    * Use cases: Default dispatcher, Bulkheading
    * Driven by: `java.util.concurrent.ExecutorService`.
      Specify using "executor" using "fork-join-executor", "thread-pool-executor" or the FQCN of
      an `akka.dispatcher.ExecutorServiceConfigurator`.

* **PinnedDispatcher**

    This dispatcher dedicates a unique thread for each actor using it; i.e.
    each actor will have its own thread pool with only one thread in the pool.

    * Sharability: None
    * Mailboxes: Any, creates one per Actor
    * Use cases: Bulkheading
    * Driven by: Any `akka.dispatch.ThreadPoolExecutorConfigurator`.
      By default a "thread-pool-executor".

* **CallingThreadDispatcher**

    This dispatcher runs invocations on the current thread only. This
    dispatcher does not create any new threads, but it can be used from
    different threads concurrently for the same actor.
    See @ref:[CallingThreadDispatcher](../testing.md#callingthreaddispatcher)
    for details and restrictions.

    * Sharability: Unlimited
    * Mailboxes: Any, creates one per Actor per Thread (on demand)
    * Use cases: Debugging and testing
    * Driven by: The calling thread (duh)

## Dispatcher aliases

When a dispatcher is looked up, and the given setting contains a string rather than a dispatcher config block,
the lookup will treat it as an alias, and follow that string to an alternate location for a dispatcher config.
If the dispatcher config is referenced both through an alias and through the absolute path only one dispatcher will
be used and shared among the two ids.

Example: configuring `internal-dispatcher` to be an alias for `default-dispatcher`:

```
akka.actor.internal-dispatcher = akka.actor.default-dispatcher
```

## Blocking Needs Careful Management

In some cases it is unavoidable to do blocking operations, i.e. to put a thread
to sleep for an indeterminate time, waiting for an external event to occur.
Examples are legacy RDBMS drivers or messaging APIs, and the underlying reason
is typically that (network) I/O occurs under the covers.

How to deal with this is currently documented as part of the [Classic Dispatcher](../dispatchers.md#blocking-needs-careful-management)
