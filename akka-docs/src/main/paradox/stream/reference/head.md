# head

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving,
after this the stream is canceled.

## Signature

## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which completes with the first value arriving,
after this the stream is canceled. If no element is emitted, the @scala[`Future`] @java[`CompletionStage`] is failed.


@@@div { .callout }

**cancels** after receiving one element

**backpressures** never

@@@

## Example

