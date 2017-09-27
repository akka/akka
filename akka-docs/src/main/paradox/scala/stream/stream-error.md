# Error Handling in Streams

When a stage in a stream fails this will normally lead to the entire stream being torn down.
Each of the stages downstream gets informed about the failure and each upstream stage sees a cancellation.

In many cases you may want to avoid complete stream failure, this can be done in a few different ways:

 * `recover` to emit a final element then complete the stream normally on upstream failure 
 * `recoverWithRetries` to create a new upstream and start consuming from that on failure
 * Restarting sections of the stream after a backoff
 * Using a supervision strategy for stages that support it
 
In addition to these built in tools for error handling, a common pattern is to wrap the stream 
inside an actor, and have the actor restart the entire stream on failure.
 
## Recover

`recover` allows you to emit a final element and then complete the stream on an upstream failure.
Deciding which exceptions should be recovered is done through a `PartialFunction`. If an exception
does not have a @scala[matching case] @java[match defined] the stream is failed. 

Recovering can be useful if you want to gracefully complete a stream on failure while letting 
downstream know that there was a failure.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #recover }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #recover }

This will output:

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #recover-output }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #recover-output }


## Recover with retries

`recoverWithRetries` allows you to put a new upstream in place of the failed one, recovering 
stream failures up to a specified maximum number of times. 

Deciding which exceptions should be recovered is done through a `PartialFunction`. If an exception
does not have a @scala[matching case] @java[match defined] the stream is failed.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #recoverWithRetries }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #recoverWithRetries }

This will output:


Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #recoverWithRetries-output }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #recoverWithRetries-output }


<a id="restart-with-backoff"></a>

## Delayed restarts with a backoff stage

Just as Akka provides the @ref:[backoff supervision pattern for actors](../general/supervision.md#backoff-supervisor), Akka streams
also provides a `RestartSource`, `RestartSink` and `RestartFlow` for implementing the so-called *exponential backoff 
supervision strategy*, starting a stage again when it fails, each time with a growing time delay between restarts.

This pattern is useful when the stage fails or completes because some external resource is not available
and we need to give it some time to start-up again. One of the prime examples when this is useful is
when a WebSocket connection fails due to the HTTP server it's running on going down, perhaps because it is overloaded. 
By using an exponential backoff, we avoid going into a tight reconnect look, which both gives the HTTP server some time
to recover, and it avoids using needless resources on the client side.

The following snippet shows how to create a backoff supervisor using @scala[`akka.stream.scaladsl.RestartSource`] 
@java[`akka.stream.javadsl.RestartSource`] which will supervise the given `Source`. The `Source` in this case is a 
stream of Server Sent Events, produced by akka-http. If the stream fails or completes at any point, the request will
be made again, in increasing intervals of 3, 6, 12, 24 and finally 30 seconds (at which point it will remain capped due
to the `maxBackoff` parameter):

Scala
:   @@snip [RestartDocSpec.scala]($code$/scala/docs/stream/RestartDocSpec.scala) { #restart-with-backoff-source }

Java
:   @@snip [RestartDocTest.java]($code$/java/jdocs/stream/RestartDocTest.java) { #restart-with-backoff-source }

Using a `randomFactor` to add a little bit of additional variance to the backoff intervals
is highly recommended, in order to avoid multiple streams re-start at the exact same point in time,
for example because they were stopped due to a shared resource such as the same server going down
and re-starting after the same configured interval. By adding additional randomness to the
re-start intervals the streams will start in slightly different points in time, thus avoiding
large spikes of traffic hitting the recovering server or other resource that they all need to contact.

The above `RestartSource` will never terminate unless the `Sink` it's fed into cancels. It will often be handy to use
it in combination with a @ref:[`KillSwitch`](stream-dynamic.md#kill-switch), so that you can terminate it when needed:

Scala
:   @@snip [RestartDocSpec.scala]($code$/scala/docs/stream/RestartDocSpec.scala) { #with-kill-switch }

Java
:   @@snip [RestartDocTest.java]($code$/java/jdocs/stream/RestartDocTest.java) { #with-kill-switch }

Sinks and flows can also be supervised, using @scala[`akka.stream.scaladsl.RestartSink` and `akka.stream.scaladsl.RestartFlow`] 
@java[`akka.stream.scaladsl.RestartSink` and `akka.stream.scaladsl.RestartFlow`]. The `RestartSink` is restarted when 
it cancels, while the `RestartFlow` is restarted when either the in port cancels, the out port completes, or the out
 port sends an error.

## Supervision Strategies

@@@ note

The stages that support supervision strategies are explicitly documented to do so, if there is
nothing in the documentation of a stage saying that it adheres to the supervision strategy it
means it fails rather than applies supervision.

@@@

The error handling strategies are inspired by actor supervision strategies, but the semantics 
have been adapted to the domain of stream processing. The most important difference is that 
supervision is not automatically applied to stream stages but instead something that each stage 
has to implement explicitly. 

For many stages it may not even make sense to implement support for supervision strategies,
this is especially true for stages connecting to external technologies where for example a
failed connection will likely still fail if a new connection is tried immediately (see 
@ref:[Restart with back off](#restart-with-backoff) for such scenarios). 

For stages that do implement supervision, the strategies for how to handle exceptions from 
processing stream elements can be selected when materializing the stream through use of an attribute. 

There are three ways to handle exceptions from application code:

 * `Stop` - The stream is completed with failure.
 * `Resume` - The element is dropped and the stream continues.
 * `Restart` - The element is dropped and the stream continues after restarting the stage.
Restarting a stage means that any accumulated state is cleared. This is typically
performed by creating a new instance of the stage.

By default the stopping strategy is used for all exceptions, i.e. the stream will be completed with
failure when an exception is thrown.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #stop }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #stop }

The default supervision strategy for a stream can be defined on the settings of the materializer.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #resume }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #resume }

Here you can see that all `ArithmeticException` will resume the processing, i.e. the
elements that cause the division by zero are effectively dropped.

@@@ note

Be aware that dropping elements may result in deadlocks in graphs with
cycles, as explained in @ref:[Graph cycles, liveness and deadlocks](stream-graphs.md#graph-cycles).

@@@

The supervision strategy can also be defined for all operators of a flow.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #resume-section }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #resume-section }

`Restart` works in a similar way as `Resume` with the addition that accumulated state,
if any, of the failing processing stage will be reset.

Scala
:   @@snip [FlowErrorDocSpec.scala]($code$/scala/docs/stream/FlowErrorDocSpec.scala) { #restart-section }

Java
:   @@snip [FlowErrorDocTest.java]($code$/java/jdocs/stream/FlowErrorDocTest.java) { #restart-section }

### Errors from mapAsync

Stream supervision can also be applied to the futures of `mapAsync` and `mapAsyncUnordered` even if such
failures happen in the future rather than inside the stage itself.

Let's say that we use an external service to lookup email addresses and we would like to
discard those that cannot be found.

We start with the tweet stream of authors:

Scala
:   @@snip [IntegrationDocSpec.scala]($code$/scala/docs/stream/IntegrationDocSpec.scala) { #tweet-authors }

Java
:   @@snip [IntegrationDocTest.java]($code$/java/jdocs/stream/IntegrationDocTest.java) { #tweet-authors }

Assume that we can lookup their email address using:

Scala
:   @@snip [IntegrationDocSpec.scala]($code$/scala/docs/stream/IntegrationDocSpec.scala) { #email-address-lookup2 }

Java
:   @@snip [IntegrationDocTest.java]($code$/java/jdocs/stream/IntegrationDocTest.java) { #email-address-lookup2 }

The @scala[`Future`] @java[`CompletionStage`] is completed @scala[with `Failure`] @java[normally] if the email is not found.

Transforming the stream of authors to a stream of email addresses by using the `lookupEmail`
service can be done with `mapAsync` and we use @scala[`Supervision.resumingDecider`] @java[`Supervision.getResumingDecider`] to drop
unknown email addresses:

Scala
:   @@snip [IntegrationDocSpec.scala]($code$/scala/docs/stream/IntegrationDocSpec.scala) { #email-addresses-mapAsync-supervision }

Java
:   @@snip [IntegrationDocTest.java]($code$/java/jdocs/stream/IntegrationDocTest.java) { #email-addresses-mapAsync-supervision }

If we would not use `Resume` the default stopping strategy would complete the stream
with failure on the first @scala[`Future`] @java[`CompletionStage`] that was completed @scala[with `Failure`]@java[exceptionally].

