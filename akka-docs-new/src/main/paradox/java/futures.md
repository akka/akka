<a id="futures-java"></a>
# Futures

## Introduction

In the Scala Standard Library, a [Future](http://en.wikipedia.org/wiki/Futures_and_promises) is a data structure
used to retrieve the result of some concurrent operation. This result can be accessed synchronously (blocking)
or asynchronously (non-blocking). To be able to use this from Java, Akka provides a java friendly interface
in `akka.dispatch.Futures`.

See also @ref:[Java 8 and Scala Compatibility](scala-compat.md) for Java compatibility.

## Execution Contexts

In order to execute callbacks and operations, Futures need something called an `ExecutionContext`,
which is very similar to a `java.util.concurrent.Executor`. if you have an `ActorSystem` in scope,
it will use its default dispatcher as the `ExecutionContext`, or you can use the factory methods provided
by the `ExecutionContexts` class to wrap `Executors` and `ExecutorServices`, or even create your own.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports1 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #diy-execution-context }

## Use with Actors

There are generally two ways of getting a reply from an `AbstractActor`: the first is by a sent message (`actorRef.tell(msg, sender)`),
which only works if the original sender was an `AbstractActor`) and the second is through a `Future`.

Using the `ActorRef`'s `ask` method to send a message will return a `Future`.
To wait for and retrieve the actual result the simplest method is:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports1 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #ask-blocking }

This will cause the current thread to block and wait for the `AbstractActor` to 'complete' the `Future` with it's reply.
Blocking is discouraged though as it can cause performance problem.
The blocking operations are located in `Await.result` and `Await.ready` to make it easy to spot where blocking occurs.
Alternatives to blocking are discussed further within this documentation.
Also note that the `Future` returned by an `AbstractActor` is a `Future<Object>` since an `AbstractActor` is dynamic.
That is why the cast to `String` is used in the above sample.

@@@ warning

`Await.result` and `Await.ready` are provided for exceptional situations where you **must** block,
a good rule of thumb is to only use them if you know why you **must** block. For all other cases, use
asynchronous composition as described below.

@@@

To send the result of a `Future` to an `Actor`, you can use the `pipe` construct:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #pipe-to }

## Use Directly

A common use case within Akka is to have some computation performed concurrently without needing
the extra utility of an `AbstractActor`. If you find yourself creating a pool of `AbstractActor`s for the sole reason
of performing a calculation in parallel, there is an easier (and faster) way:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports2 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #future-eval }

In the above code the block passed to `future` will be executed by the default `Dispatcher`,
with the return value of the block used to complete the `Future` (in this case, the result would be the string: "HelloWorld").
Unlike a `Future` that is returned from an `AbstractActor`, this `Future` is properly typed,
and we also avoid the overhead of managing an `AbstractActor`.

You can also create already completed Futures using the `Futures` class, which can be either successes:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #successful }

Or failures:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #failed }

It is also possible to create an empty `Promise`, to be filled later, and obtain the corresponding `Future`:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #promise }

For these examples `PrintResult` is defined as follows:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #print-result }

## Functional Futures

Scala's `Future` has several monadic methods that are very similar to the ones used by `Scala`'s collections.
These allow you to create 'pipelines' or 'streams' that the result will travel through.

### Future is a Monad

The first method for working with `Future` functionally is `map`. This method takes a `Mapper` which performs
some operation on the result of the `Future`, and returning a new result.
The return value of the `map` method is another `Future` that will contain the new result:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports2 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #map }

In this example we are joining two strings together within a `Future`. Instead of waiting for f1 to complete,
we apply our function that calculates the length of the string using the `map` method.
Now we have a second `Future`, f2, that will eventually contain an `Integer`.
When our original `Future`, f1, completes, it will also apply our function and complete the second `Future`
with its result. When we finally `get` the result, it will contain the number 10.
Our original `Future` still contains the string "HelloWorld" and is unaffected by the `map`.

Something to note when using these methods: passed work is always dispatched on the provided `ExecutionContext`. Even if
the `Future` has already been completed, when one of these methods is called.

### Composing Futures

It is very often desirable to be able to combine different Futures with each other,
below are some examples on how that can be done in a non-blocking fashion.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports3 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #sequence }

To better explain what happened in the example, `Future.sequence` is taking the `Iterable<Future<Integer>>`
and turning it into a `Future<Iterable<Integer>>`. We can then use `map` to work with the `Iterable<Integer>` directly,
and we aggregate the sum of the `Iterable`.

The `traverse` method is similar to `sequence`, but it takes a sequence of `A` and applies a function from `A` to `Future<B>`
and returns a `Future<Iterable<B>>`, enabling parallel `map` over the sequence, if you use `Futures.future` to create the `Future`.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports4 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #traverse }

It's as simple as that!

Then there's a method that's called `fold` that takes a start-value,
a sequence of `Future`:s and a function from the type of the start-value, a timeout,
and the type of the futures and returns something with the same type as the start-value,
and then applies the function to all elements in the sequence of futures, non-blockingly,
the execution will be started when the last of the Futures is completed.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports5 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #fold }

That's all it takes!

If the sequence passed to `fold` is empty, it will return the start-value, in the case above, that will be empty String.
In some cases you don't have a start-value and you're able to use the value of the first completing `Future`
in the sequence as the start-value, you can use `reduce`, it works like this:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports6 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #reduce }

Same as with `fold`, the execution will be started when the last of the Futures is completed, you can also parallelize
it by chunking your futures into sub-sequences and reduce them, and then reduce the reduced results again.

This is just a sample of what can be done.

## Callbacks

Sometimes you just want to listen to a `Future` being completed, and react to that not by creating a new Future, but by side-effecting.
For this Scala supports `onComplete`, `onSuccess` and `onFailure`, of which the last two are specializations of the first.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #onSuccess }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #onFailure }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #onComplete }

## Ordering

Since callbacks are executed in any order and potentially in parallel,
it can be tricky at the times when you need sequential ordering of operations.
But there's a solution! And it's name is `andThen`, and it creates a new `Future` with
the specified callback, a `Future` that will have the same result as the `Future` it's called on,
which allows for ordering like in the following sample:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #and-then }

## Auxiliary methods

`Future` `fallbackTo` combines 2 Futures into a new `Future`, and will hold the successful value of the second `Future`
if the first `Future` fails.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #fallback-to }

You can also combine two Futures into a new `Future` that will hold a tuple of the two Futures successful results,
using the `zip` operation.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #zip }

## Exceptions

Since the result of a `Future` is created concurrently to the rest of the program, exceptions must be handled differently.
It doesn't matter if an `AbstractActor` or the dispatcher is completing the `Future`, if an `Exception` is caught
the `Future` will contain it instead of a valid result. If a `Future` does contain an `Exception`,
calling `Await.result` will cause it to be thrown again so it can be handled properly.

It is also possible to handle an `Exception` by returning a different result.
This is done with the `recover` method. For example:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #recover }

In this example, if the actor replied with a `akka.actor.Status.Failure` containing the `ArithmeticException`,
our `Future` would have a result of 0. The `recover` method works very similarly to the standard try/catch blocks,
so multiple `Exception`s can be handled in this manner, and if an `Exception` is not handled this way
it will behave as if we hadn't used the `recover` method.

You can also use the `recoverWith` method, which has the same relationship to `recover` as `flatMap` has to `map`,
and is use like this:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #try-recover }

## After

`akka.pattern.Patterns.after` makes it easy to complete a `Future` with a value or exception after a timeout.

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #imports7 }

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #after }

## Java 8, CompletionStage and CompletableFuture

Starting with Akka 2.4.2 we have begun to introduce Java 8 `java.util.concurrent.CompletionStage` in Java APIs.
It's a `scala.concurrent.Future` counterpart in Java; conversion from `scala.concurrent.Future` is done using
`scala-java8-compat` library.

Unlike `scala.concurrent.Future` which has async methods only, `CompletionStage` has *async* and *non-async* methods.

The `scala-java8-compat` library returns its own implementation of `CompletionStage` which delegates all *non-async*
methods to their *async* counterparts. The implementation extends standard Java `CompletableFuture`.
Java 8 `CompletableFuture` creates a new instance of `CompletableFuture` for any new stage,
which means `scala-java8-compat` implementation is not used after the first mapping method.

@@@ note

After adding any additional computation stage to `CompletionStage` returned by `scala-java8-compat`
(e.g. `CompletionStage` instances returned by Akka) it falls back to standard behaviour of Java `CompletableFuture`.

@@@

Actions supplied for dependent completions of *non-async* methods may be performed by the thread
that completes the current `CompletableFuture`, or by any other caller of a completion method.

All *async* methods without an explicit Executor are performed using the `ForkJoinPool.commonPool()` executor.

### Non-async methods

When non-async methods are applied on a not yet completed `CompletionStage`, they are completed by
the thread which completes initial `CompletionStage`:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #apply-completion-thread }

In this example Scala `Future` is converted to `CompletionStage` just like Akka does.
The completion is delayed: we are calling `thenApply` multiple times on a not yet complete `CompletionStage`, then
complete the `Future`.

First `thenApply` is actually performed on `scala-java8-compat` instance and computational stage (lambda) execution
is delegated to default Java `thenApplyAsync` which is executed on `ForkJoinPool.commonPool()`.

Second and third `thenApply` methods are executed on Java 8 `CompletableFuture` instance which executes computational
stages on the thread which completed the first stage. It is never executed on a thread of Scala `Future` because
default `thenApply` breaks the chain and executes on `ForkJoinPool.commonPool()`.

In the next example `thenApply` methods are executed on an already completed `Future`/`CompletionStage`:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #apply-main-thread }

First `thenApply` is still executed on `ForkJoinPool.commonPool()` (because it is actually `thenApplyAsync`
which is always executed on global Java pool).

Then we wait for stages to complete so second and third `thenApply` are executed on completed `CompletionStage`,
and stages are executed on the current thread - the thread which called second and third `thenApply`.

### Async methods

As mentioned above, default *async* methods are always executed on `ForkJoinPool.commonPool()`:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #apply-async-default }

`CompletionStage` also has *async* methods which take `Executor` as a second parameter, just like `Future`:

@@snip [FutureDocTest.java](code/jdocs/future/FutureDocTest.java) { #apply-async-executor }

This example is behaving like `Future`: every stage is executed on an explicitly specified `Executor`.

@@@ note

When in doubt, async methods with explicit executor should be used. Always async methods with a dedicated
executor/dispatcher for long-running or blocking computations, such as IO operations.

@@@

See also:

 * [CompletionStage](https://docs.oracle.com/javase/8/jdocs/api/java/util/concurrent/CompletionStage.html)
 * [CompletableFuture](https://docs.oracle.com/javase/8/jdocs/api/java/util/concurrent/CompletableFuture.html)
 * [scala-java8-compat](https://github.com/scala/scala-java8-compat)