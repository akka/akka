# actorRefWithAck

Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.

## Description

Send the elements from the stream to an `ActorRef` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.


@@@div { .callout }

**cancels** when the actor terminates

**backpressures** when the actor acknowledgement has not arrived

@@@

## Example

