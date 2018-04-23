# zip

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.

@ref[Fan-in stages](../index.md#fan-in-stages)

@@@div { .group-scala }

## Signature

@@signature [Flow.scala]($akka$/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #zip }

@@@

## Description

Combines elements from each of multiple sources into @scala[tuples] @java[*Pair*] and passes the @scala[tuples] @java[pairs] downstream.


@@@div { .callout }

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

@@@

