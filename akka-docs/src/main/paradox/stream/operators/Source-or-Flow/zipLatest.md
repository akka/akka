# zipLatest

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream, picking always the latest element of each.

@ref[Fan-in operators](../index.md#fan-in-operators)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zipLatest }

@@@

## Description

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream, picking always the latest element of each.

No element is emitted until at least one element from each Source becomes available.
 
## Reactive Streams semantics

@@@div { .callout }

**emits** when all of the inputs have at least an element available, and then each time an element becomes
          available on either of the inputs

**backpressures** when downstream backpressures

**completes** when any upstream completes

**cancels** when downstream cancels

@@@

