# zipLatestWith

Combines elements from multiple sources through a `combine` function and passes the returned value downstream, picking always the latest element of each.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

@apidoc[Source.zipLatestWith](Source) { scala="#zipLatestWith[Out2,Out3](that:akka.stream.Graph[akka.stream.SourceShape[Out2],_])(combine:(Out,Out2)=&gt;Out3):FlowOps.this.Repr[Out3]" java="#zipLatestWith(akka.stream.Graph,akka.japi.function.Function2)" }
@apidoc[Flow.zipLatestWith](Flow) { scala="#zipLatestWith[Out2,Out3](that:akka.stream.Graph[akka.stream.SourceShape[Out2],_])(combine:(Out,Out2)=&gt;Out3):FlowOps.this.Repr[Out3]" java="#zipLatestWith(akka.stream.Graph,akka.japi.function.Function2)" }


## Description

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream, picking always the latest element of each.

No element is emitted until at least one element from each Source becomes available. Whenever a new
element appears, the zipping function is invoked with a tuple containing the new element and the last seen element of the other stream.

## Reactive Streams semantics

@@@div { .callout }

**emits** all of the inputs have at least an element available, and then each time an element becomes
          available on either of the inputs

**backpressures** when downstream backpressures

**completes** when any upstream completes

**cancels** when downstream cancels

@@@
