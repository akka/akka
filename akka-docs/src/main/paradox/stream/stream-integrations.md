# Integration

## Dependency

To use Akka Streams, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary_version$"
  version="$akka.version$"
}

## Integrating with Actors

For piping the elements of a stream as messages to an ordinary actor you can use
`ask` in a `mapAsync` or use `Sink.actorRefWithAck`.

Messages can be sent to a stream with `Source.queue` or via the `ActorRef` that is
materialized by `Source.actorRef`.

### ask

@@@ note
  See also: @ref[Flow.ask operator reference docs](operators/Source-or-Flow/ask.md), @ref[ActorFlow.ask operator reference docs](operators/ActorFlow/ask.md) for Akka Typed
@@@

A nice way to delegate some processing of elements in a stream to an actor is to use `ask`.
The back-pressure of the stream is maintained by the @scala[`Future`]@java[`CompletionStage`] of
the `ask` and the mailbox of the actor will not be filled with more messages than the given
`parallelism` of the `ask` operator (similarly to how the `mapAsync` operator works).

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #ask }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #ask }

Note that the messages received in the actor will be in the same order as
the stream elements, i.e. the `parallelism` does not change the ordering
of the messages. There is a performance advantage of using parallelism > 1
even though the actor will only process one message at a time because then there
is already a message in the mailbox when the actor has completed previous
message.

The actor must reply to the @scala[`sender()`]@java[`getSender()`] for each message from the stream. That
reply will complete the  @scala[`Future`]@java[`CompletionStage`] of the `ask` and it will be the element that is emitted downstreams.

In case the target actor is stopped, the operator will fail with an `AskStageTargetActorTerminatedException`

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #ask-actor }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #ask-actor }

The stream can be completed with failure by sending `akka.actor.Status.Failure` as reply from the actor.

If the `ask` fails due to timeout the stream will be completed with
`TimeoutException` failure. If that is not desired outcome you can use `recover`
on the `ask` @scala[`Future`]@java[`CompletionStage`], or use the other "restart" operators to restart it.

If you don't care about the reply values and only use them as back-pressure signals you
can use `Sink.ignore` after the `ask` operator and then actor is effectively a sink
of the stream.

Note that while you may implement the same concept using `mapAsync`, that style would not be aware of the actor terminating.

If you are intending to ask multiple actors by using @ref:[Actor routers](../routing.md), then
you should use `mapAsyncUnordered` and perform the ask manually in there, as the ordering of the replies is not important,
since multiple actors are being asked concurrently to begin with, and no single actor is the one to be watched by the operator.

### Sink.actorRefWithAck

@@@ note
  See also: @ref[Sink.actorRefWithAck operator reference docs](operators/Sink/actorRefWithAck.md)
@@@

The sink sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
First element is always *onInitMessage*, then stream is waiting for the given acknowledgement message
from the given actor which means that it is ready to process elements. It also requires the given acknowledgement
message after each stream element to make back-pressure work.

If the target actor terminates the stream will be cancelled. When the stream is completed successfully the
given `onCompleteMessage` will be sent to the destination actor. When the stream is completed with
failure a `akka.actor.Status.Failure` message will be sent to the destination actor.

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #actorRefWithAck }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #actorRefWithAck }

The receiving actor would then need to be implemented similar to the following:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #actorRefWithAck-actor }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #actorRefWithAck-actor }

Note that replying to the sender of the elements (the "stream") is required as lack of those ack signals would be interpreted
as back-pressure (as intended), and no new elements will be sent into the actor until it acknowledges some elements.
Handling the other signals while is not required, however is a good practice, to see the state of the streams lifecycle
in the connected actor as well. Technically it is also possible to use multiple sinks targeting the same actor,
however it is not common practice to do so, and one should rather investigate using a `Merge` operator for this purpose.


@@@ note

Using `Sink.actorRef` or ordinary `tell` from a `map` or `foreach` operator means that there is
no back-pressure signal from the destination actor, i.e. if the actor is not consuming the messages
fast enough the mailbox of the actor will grow, unless you use a bounded mailbox with zero
*mailbox-push-timeout-time* or use a rate limiting operator in front. It's often better to
use `Sink.actorRefWithAck` or `ask` in `mapAsync`, though.

@@@

### Source.queue

`Source.queue` is an improvement over `Sink.actorRef`, since it can provide backpressure.
The `offer` method returns a @scala[`Future`]@java[`CompletionStage`], which completes with the result of the enqueue operation.

`Source.queue` can be used for emitting elements to a stream from an actor (or from anything running outside
the stream). The elements will be buffered until the stream can process them. You can `offer` elements to
the queue and they will be emitted to the stream if there is demand from downstream, otherwise they will
be buffered until request for demand is received.

Use overflow strategy `akka.stream.OverflowStrategy.backpressure` to avoid dropping of elements if the
buffer is full, instead the returned @scala[`Future`]@java[`CompletionStage`] does not complete until there is space in the
buffer and `offer` should not be called again until it completes.

Using `Source.queue` you can push elements to the queue and they will be emitted to the stream if there is
demand from downstream, otherwise they will be buffered until request for demand is received. Elements in the buffer
will be discarded if downstream is terminated.

You could combine it with the @ref[`throttle`](operators/Source-or-Flow/throttle.md) operator is used to slow down the stream to `5 element` per `3 seconds` and other patterns.

`SourceQueue.offer` returns @scala[`Future[QueueOfferResult]`]@java[`CompletionStage<QueueOfferResult>`] which completes with `QueueOfferResult.Enqueued`
if element was added to buffer or sent downstream. It completes with `QueueOfferResult.Dropped` if element
was dropped. Can also complete  with `QueueOfferResult.Failure` - when stream failed or
`QueueOfferResult.QueueClosed` when downstream is completed.

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #source-queue }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #source-queue }

When used from an actor you typically `pipe` the result of the @scala[`Future`]@java[`CompletionStage`] back to the actor to
continue processing.

### Source.actorRef

Messages sent to the actor that is materialized by `Source.actorRef` will be emitted to the
stream if there is demand from downstream, otherwise they will be buffered until request for
demand is received.

Depending on the defined `OverflowStrategy` it might drop elements if there is no space
available in the buffer. The strategy `OverflowStrategy.backpressure` is not supported
for this Source type, i.e. elements will be dropped if the buffer is filled by sending
at a rate that is faster than the stream can consume. You should consider using `Source.queue`
if you want a backpressured actor interface.

The stream can be completed successfully by sending `akka.actor.Status.Success` to the actor reference.
If the content is `akka.stream.CompletionStrategy.immediately` the completion will be signaled immidiately.
If the content is `akka.stream.CompletionStrategy.draining` already buffered elements will be signaled before siganling completion.
Any other content will be ignored and fall back to the draining behaviour. 

The stream can be completed with failure by sending `akka.actor.Status.Failure` to the
actor reference.

Note: Sending a `PoisonPill` is deprecated and will be ignored in the future.

The actor will be stopped when the stream is completed, failed or cancelled from downstream,
i.e. you can watch it to get notified when that happens.


Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #source-actorRef }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #source-actorRef }

## Integrating with External Services

Stream transformations and side effects involving external non-stream based services can be
performed with `mapAsync` or `mapAsyncUnordered`.

For example, sending emails to the authors of selected tweets using an external
email service:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #email-server-send }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #email-server-send }

We start with the tweet stream of authors:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #tweet-authors}

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #tweet-authors }

Assume that we can lookup their email address using:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #email-address-lookup }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #email-address-lookup }

Transforming the stream of authors to a stream of email addresses by using the `lookupEmail`
service can be done with `mapAsync`:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #email-addresses-mapAsync }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #email-addresses-mapAsync }

Finally, sending the emails:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #send-emails }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #send-emails }

`mapAsync` is applying the given function that is calling out to the external service to
each of the elements as they pass through this processing step. The function returns a @scala[`Future`]@java[`CompletionStage`]
and the value of that future will be emitted downstreams. The number of Futures
that shall run in parallel is given as the first argument to `mapAsync`.
These Futures may complete in any order, but the elements that are emitted
downstream are in the same order as received from upstream.

That means that back-pressure works as expected. For example if the `emailServer.send`
is the bottleneck it will limit the rate at which incoming tweets are retrieved and
email addresses looked up.

The final piece of this pipeline is to generate the demand that pulls the tweet
authors information through the emailing pipeline: we attach a `Sink.ignore`
which makes it all run. If our email process would return some interesting data
for further transformation then we would not ignore it but send that
result stream onwards for further processing or storage.

Note that `mapAsync` preserves the order of the stream elements. In this example the order
is not important and then we can use the more efficient `mapAsyncUnordered`:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #external-service-mapAsyncUnordered }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #external-service-mapAsyncUnordered }

In the above example the services conveniently returned a  @scala[`Future`]@java[`CompletionStage`] of the result.
If that is not the case you need to wrap the call in a  @scala[`Future`]@java[`CompletionStage`]. If the service call
involves blocking you must also make sure that you run it on a dedicated execution context, to
avoid starvation and disturbance of other tasks in the system.

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #blocking-mapAsync }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #blocking-mapAsync }

The configuration of the `"blocking-dispatcher"` may look something like:

@@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #blocking-dispatcher-config }

An alternative for blocking calls is to perform them in a `map` operation, still using a
dedicated dispatcher for that operation.

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #blocking-map }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #blocking-map }

However, that is not exactly the same as `mapAsync`, since the `mapAsync` may run
several calls concurrently, but `map` performs them one at a time.

For a service that is exposed as an actor, or if an actor is used as a gateway in front of an
external service, you can use `ask`:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #save-tweets }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #save-tweets }

Note that if the `ask` is not completed within the given timeout the stream is completed with failure.
If that is not desired outcome you can use `recover` on the `ask` @scala[`Future`]@java[`CompletionStage`].

### Illustrating ordering and parallelism

Let us look at another example to get a better understanding of the ordering
and parallelism characteristics of `mapAsync` and `mapAsyncUnordered`.

Several `mapAsync` and `mapAsyncUnordered` futures may run concurrently.
The number of concurrent futures are limited by the downstream demand.
For example, if 5 elements have been requested by downstream there will be at most 5
futures in progress.

`mapAsync` emits the future results in the same order as the input elements
were received. That means that completed results are only emitted downstream
when earlier results have been completed and emitted. One slow call will thereby
delay the results of all successive calls, even though they are completed before
the slow call.

`mapAsyncUnordered` emits the future results as soon as they are completed, i.e.
it is possible that the elements are not emitted downstream in the same order as
received from upstream. One slow call will thereby not delay the results of faster
successive calls as long as there is downstream demand of several elements.

Here is a fictive service that we can use to illustrate these aspects.

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #sometimes-slow-service }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #sometimes-slow-service }

Elements starting with a lower case character are simulated to take longer time
to process.

Here is how we can use it with `mapAsync`:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #sometimes-slow-mapAsync }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #sometimes-slow-mapAsync }

The output may look like this:

```
before: a
before: B
before: C
before: D
running: a (1)
running: B (2)
before: e
running: C (3)
before: F
running: D (4)
before: g
before: H
completed: C (3)
completed: B (2)
completed: D (1)
completed: a (0)
after: A
after: B
running: e (1)
after: C
after: D
running: F (2)
before: i
before: J
running: g (3)
running: H (4)
completed: H (2)
completed: F (3)
completed: e (1)
completed: g (0)
after: E
after: F
running: i (1)
after: G
after: H
running: J (2)
completed: J (1)
completed: i (0)
after: I
after: J
```

Note that `after` lines are in the same order as the `before` lines even
though elements are `completed` in a different order. For example `H`
is `completed` before `g`, but still emitted afterwards.

The numbers in parenthesis illustrates how many calls that are in progress at
the same time. Here the downstream demand and thereby the number of concurrent
calls are limited by the buffer size (4) of the `ActorMaterializerSettings`.

Here is how we can use the same service with `mapAsyncUnordered`:

Scala
:   @@snip [IntegrationDocSpec.scala](/akka-docs/src/test/scala/docs/stream/IntegrationDocSpec.scala) { #sometimes-slow-mapAsyncUnordered }

Java
:   @@snip [IntegrationDocTest.java](/akka-docs/src/test/java/jdocs/stream/IntegrationDocTest.java) { #sometimes-slow-mapAsyncUnordered }

The output may look like this:

```
before: a
before: B
before: C
before: D
running: a (1)
running: B (2)
before: e
running: C (3)
before: F
running: D (4)
before: g
before: H
completed: B (3)
completed: C (1)
completed: D (2)
after: B
after: D
running: e (2)
after: C
running: F (3)
before: i
before: J
completed: F (2)
after: F
running: g (3)
running: H (4)
completed: H (3)
after: H
completed: a (2)
after: A
running: i (3)
running: J (4)
completed: J (3)
after: J
completed: e (2)
after: E
completed: g (1)
after: G
completed: i (0)
after: I
```

Note that `after` lines are not in the same order as the `before` lines. For example
`H` overtakes the slow `G`.

The numbers in parenthesis illustrates how many calls that are in progress at
the same time. Here the downstream demand and thereby the number of concurrent
calls are limited by the buffer size (4) of the `ActorMaterializerSettings`.

<a id="reactive-streams-integration"></a>
## Integrating with Reactive Streams

[Reactive Streams](http://reactive-streams.org/) defines a standard for asynchronous stream processing with non-blocking
back pressure. It makes it possible to plug together stream libraries that adhere to the standard.
Akka Streams is one such library.

An incomplete list of other implementations:

 * [Reactor (1.1+)](https://github.com/reactor/reactor)
 * [RxJava](https://github.com/ReactiveX/RxJavaReactiveStreams)
 * [Ratpack](http://www.ratpack.io/manual/current/streams.html)
 * [Slick](http://slick.lightbend.com)

The two most important interfaces in Reactive Streams are the `Publisher` and `Subscriber`.

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #imports }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #imports }

Let us assume that a library provides a publisher of tweets:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #tweets-publisher }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #tweets-publisher }

and another library knows how to store author handles in a database:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #author-storage-subscriber }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #author-storage-subscriber }

Using an Akka Streams `Flow` we can transform the stream and connect those:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #authors #connect-all }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #authors #connect-all }

The `Publisher` is used as an input `Source` to the flow and the
`Subscriber` is used as an output `Sink`.

A `Flow` can also be also converted to a `RunnableGraph[Processor[In, Out]]` which
materializes to a `Processor` when `run()` is called. `run()` itself can be called multiple
times, resulting in a new `Processor` instance each time.

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #flow-publisher-subscriber }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #flow-publisher-subscriber }

A publisher can be connected to a subscriber with the `subscribe` method.

It is also possible to expose a `Source` as a `Publisher`
by using the Publisher-`Sink`:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #source-publisher }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #source-publisher }

A publisher that is created with  @scala[`Sink.asPublisher(fanout = false)`]@java[`Sink.asPublisher(AsPublisher.WITHOUT_FANOUT)`] supports only a single subscription.
Additional subscription attempts will be rejected with an `IllegalStateException`.

A publisher that supports multiple subscribers using fan-out/broadcasting is created as follows:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #author-alert-subscriber #author-storage-subscriber }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #author-alert-subscriber #author-storage-subscriber }


Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #source-fanoutPublisher }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #source-fanoutPublisher }

The input buffer size of the operator controls how far apart the slowest subscriber can be from the fastest subscriber
before slowing down the stream.

To make the picture complete, it is also possible to expose a `Sink` as a `Subscriber`
by using the Subscriber-`Source`:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #sink-subscriber }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #sink-subscriber }

It is also possible to use re-wrap `Processor` instances as a `Flow` by
passing a factory function that will create the `Processor` instances:

Scala
:   @@snip [ReactiveStreamsDocSpec.scala](/akka-docs/src/test/scala/docs/stream/ReactiveStreamsDocSpec.scala) { #use-processor }

Java
:   @@snip [ReactiveStreamsDocTest.java](/akka-docs/src/test/java/jdocs/stream/ReactiveStreamsDocTest.java) { #use-processor }

Please note that a factory is necessary to achieve reusability of the resulting `Flow`.

