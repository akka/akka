# watch

Watch a specific `ActorRef` and signal a failure downstream once the actor terminates.

## Signature

## Description

Watch a specific `ActorRef` and signal a failure downstream once the actor terminates.
The signaled failure will be an @java[@javadoc:[WatchedActorTerminatedException](akka.stream.WatchedActorTerminatedException)]
@scala[@scaladoc[WatchedActorTerminatedException](akka.stream.WatchedActorTerminatedException)].


@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

