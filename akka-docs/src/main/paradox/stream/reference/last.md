# last

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream
completes.

## Signature

## Description

Materializes into a @scala[`Future`] @java[`CompletionStage`] which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the @scala[`Future`] @java[`CompletionStage`] is failed.


@@@div { .callout }

**cancels** never

**backpressures** never

@@@

## Example

