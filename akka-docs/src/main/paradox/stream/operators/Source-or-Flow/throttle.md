# throttle

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where a function has to be provided to calculate the individual cost of each element.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.throttle](Source) { scala="#throttle(cost:Int,per:scala.concurrent.duration.FiniteDuration,maximumBurst:Int,costCalculation:Out=&gt;Int,mode:akka.stream.ThrottleMode):FlowOps.this.Repr[Out]" java="#throttle(int,java.time.Duration,int,akka.japi.function.Function,akka.stream.ThrottleMode)" }
@apidoc[Flow.throttle](Flow) { scala="#throttle(cost:Int,per:scala.concurrent.duration.FiniteDuration,maximumBurst:Int,costCalculation:Out=&gt;Int,mode:akka.stream.ThrottleMode):FlowOps.this.Repr[Out]" java="#throttle(int,java.time.Duration,int,akka.japi.function.Function,akka.stream.ThrottleMode)" }

## Description

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where
a function has to be provided to calculate the individual cost of each element.

See also @ref:[Buffers and working with rate](../../stream-rate.md) for related operators.

## Example

Imagine the server end of a streaming platform. When a client connects and requesta a video content, the server 
should return the content. Instead of serving a complete video as soon as bandwith allows, `throttle` can be used
to burst the first 30 seconds and then send a constant of 24 frames per second (let's imagine this streaming 
platform stores frames, not bytes). This allows the browser to buffer 30 seconds in case there's a network 
outage but prevents sending too much video content at once using all bandwitdh of the user's bandwitdh.

Scala
:   @@snip [Throttle.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Throttle.scala) { #throttle }

Java
:   @@snip [Throttle.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/Throttle.java) { #throttle }

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element and configured time per each element elapsed

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

