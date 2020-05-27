# throttle

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where a function has to be provided to calculate the individual cost of each element.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.throttle](Source) { scala="#throttle(cost:Int,per:scala.concurrent.duration.FiniteDuration,maximumBurst:Int,costCalculation:Out=&gt;Int,mode:akka.stream.ThrottleMode):FlowOps.this.Repr[Out]" java="#throttle(int,java.time.Duration,int,akka.japi.function.Function,akka.stream.ThrottleMode)" }
@apidoc[Flow.throttle](Flow) { scala="#throttle(cost:Int,per:scala.concurrent.duration.FiniteDuration,maximumBurst:Int,costCalculation:Out=&gt;Int,mode:akka.stream.ThrottleMode):FlowOps.this.Repr[Out]" java="#throttle(int,java.time.Duration,int,akka.japi.function.Function,akka.stream.ThrottleMode)" }

## Description

Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where
a function has to be provided to calculate the individual cost of each element.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element and configured time per each element elapsed

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

