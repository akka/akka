# unfoldResourceAsync

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

## Signature

## Description

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.
Functions return @scala[`Future`] @java[`CompletionStage`] to achieve asynchronous processing


@@@div { .callout }

**emits** when there is demand and @scala[`Future`] @java[`CompletionStage`] from read function returns value

**completes** when @scala[`Future`] @java[`CompletionStage`] from read function returns `None`

@@@

## Example

