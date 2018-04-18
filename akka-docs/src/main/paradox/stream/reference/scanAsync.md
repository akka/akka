# scanAsync

Just like `scan` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.

## Signature

## Description

Just like `scan` but receiving a function that results in a @scala[`Future`] @java[`CompletionStage`] to the next value.


@@@div { .callout }

**emits** when the @scala[`Future`] @java[`CompletionStage`] resulting from the function scanning the element resolves to the next value

**backpressures** when downstream backpressures

**completes** when upstream completes and the last @scala[`Future`] @java[`CompletionStage`] is resolved

@@@

## Example

