# watchTermination

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the stage has been completed or failed.

## Signature

## Description

Materializes to a @scala[`Future`] @java[`CompletionStage`] that will be completed with Done or failed depending whether the upstream of the stage has been completed or failed.
The stage otherwise passes through elements unchanged.


@@@div { .callout }

**emits** when input has an element available

**backpressures** when output backpressures

**completes** when upstream completes

@@@

## Example

