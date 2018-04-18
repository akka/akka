# actorRef

Send the elements from the stream to an `ActorRef`.

## Signature

## Description

Send the elements from the stream to an `ActorRef`. No backpressure so care must be taken to not overflow the inbox.


@@@div { .callout }

**cancels** when the actor terminates

**backpressures** never

@@@

## Example

