# watchTermination

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the operators has been completed or failed.

@ref[Watching status operators](../index.md#watching-status-operators)

## Signature

@apidoc[Source.watchTermination](Source) { scala="#watchTermination[Mat2]()(matF:(Mat,scala.concurrent.Future[akka.Done])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#watchTermination(akka.japi.function.Function2)" }
@apidoc[Flow.watchTermination](Flow) { scala="#watchTermination[Mat2]()(matF:(Mat,scala.concurrent.Future[akka.Done])=&gt;Mat2):FlowOpsMat.this.ReprMat[Out,Mat2]" java="#watchTermination(akka.japi.function.Function2)" }


## Description

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the operators has been completed or failed.
The operators otherwise passes through elements unchanged.

## Reactive Streams semantics

@@@div { .callout }

**emits** when input has an element available

**backpressures** when output backpressures

**completes** when upstream completes

@@@

