# seq

Collect values emitted from the stream into a collection, the collection is available through a @scala[`Future`] @java[`CompletionStage`] or
which completes when the stream completes.

## Signature

## Description

Collect values emitted from the stream into a collection, the collection is available through a @scala[`Future`] @java[`CompletionStage`] or
which completes when the stream completes. Note that the collection is bounded to @scala[`Int.MaxValue`] @java[`Integer.MAX_VALUE`],
if more element are emitted the sink will cancel the stream


@@@div { .callout }

**cancels** If too many values are collected

@@@

## Example

