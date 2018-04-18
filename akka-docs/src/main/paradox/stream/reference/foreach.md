# foreach

Invoke a given procedure for each element received.

## Signature

## Description

Invoke a given procedure for each element received. Note that it is not safe to mutate shared state from the procedure.

The sink materializes into a  @scala[`Future[Option[Done]]`] @java[`CompletionStage<Optional<Done>`] which completes when the
stream completes, or fails if the stream fails.

Note that it is not safe to mutate state from the procedure.


@@@div { .callout }

**cancels** never

**backpressures** when the previous procedure invocation has not yet completed

@@@

## Example

