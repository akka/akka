# groupBy

Demultiplex the incoming stream into separate output streams.

## Signature

## Description

Demultiplex the incoming stream into separate output streams.


@@@div { .callout }

**emits** an element for which the grouping function returns a group that has not yet been created. Emits the new group
there is an element pending for a group whose substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

@@@

## Example

