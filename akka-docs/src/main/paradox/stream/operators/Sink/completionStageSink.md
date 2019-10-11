# Sink.completionStageSink

Streams the elements to the given future sink once it successfully completes. 

@ref[Sink operators](../index.md#sink-operators)


## Description

Streams the elements through the given future flow once it successfully completes. 
If the future fails the stream is failed.

## Reactive Streams semantics

@@@div { .callout }

**cancels** if the future fails or if the created sink cancels 

**backpressures** when initialized and when created sink backpressures

@@@


