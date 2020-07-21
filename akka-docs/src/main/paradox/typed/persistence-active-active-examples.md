# Active-Active Examples

The following are more realistic examples of building systems with active-active event sourcing.

## Auction

In this example we want to show that real-world applications can be implemented by designing events in a way that they
don't conflict. In the end, you will end up with a solution based on a custom CRDT.

We are building a small auction service. It has the following operations:

 * Place a bid
 * Get the highest bid
 * Finish the auction

We model those operations as commands to be sent to the auction actor:

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #commands }

The events:

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #events }

The winner does not have to pay the highest bid but only enough to beat the second highest so the `highestCounterOffer` is in the `AuctionFinished` event. 

Let's have a look at the auction entity that will handle incoming commands:

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #command-handler }

There is nothing specific to active-active about the command handler. It is the same as a command handler for a standard `EventSourcedBehavior`.
For `OfferBid` and `AuctionFinished` we do nothing more than to emit
events corresponding to the command. For `GetHighestBid` we respond with details from the state. Note, that we overwrite the actual
offer of the highest bid here with the amount of the `highestCounterOffer`. This is done to follow the popular auction style where
the actual highest bid is never publicly revealed.

The auction entity is started with the initial parameters for the auction.
The initial state is taken from a `AuctionSetup` instance. The minimum bid is modelled as
an `initialBid`.

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #setup }


The auction moves through the following phases:

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #phase }

The closing and closed states are to model waiting for all replicas to see the result of the auction before
actually closing the action.

Let's have a look at our state class, `AuctionState` which also represents the CRDT in our example.


Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #state }

The state consists of a flag that keeps track of whether the auction is still active, the currently highest bid,
and the highest counter offer so far.

In the `eventHandler`, we handle persisted events to drive the state change. When a new bid is registered,

 * it needs to be decided whether the new bid is the winning bid or not
 * the state needs to be updated accordingly

The point of CRDTs is that the state must be end up being the same regardless of the order the events have been processed.
We can see how this works in the auction example: we are only interested in the highest bid, so, if we can define an
ordering on all bids, it should suffice to compare the new bid with currently highest to eventually end up with the globally
highest regardless of the order in which the events come in.

The ordering between bids is crucial, therefore. We need to ensure that it is deterministic and does not depend on local state
outside of our state class so that all replicas come to the same result. We define the ordering as this:

 * A higher bid wins.
 * If there's a tie between the two highest bids, the bid that was registered earlier wins. For that we keep track of the
   (local) timestamp the bid was registered.
 * We need to make sure that no timestamp is used twice in the same replica (missing in this example).
 * If there's a tie between the timestamp, we define an arbitrary but deterministic ordering on the replicas, in our case
   we just compare the name strings of the replicas. That's why we need to keep the identifier of the replica where a bid was registered
   for every `Bid`.

If the new bid was higher, we keep this one as the new highest and keep the amount of the former highest as the `highestCounterOffer`.
If the new bid was lower, we just update the `highestCounterOffer` if necessary.

Using those rules, the order of incoming does not matter. Replicas will eventually converge to the same result.

## Triggering closing

In the auction we want to ensure that all bids are seen before declaring a winner. That means that an auction can only be closed once
all replicas have seen all bids.

In the event handler above, when recovery is not running, it calls `eventTriggers`.

Scala
:   @@snip [AuctionExample](/akka-persistence-typed-tests/src/test/scala/docs/akka/persistence/typed/AAAuctionExampleSpec.scala) { #event-triggers }

The event trigger uses the `ActiveActiveContext` to decide when to trigger the Finish of the action.
When a replica saves the `AuctionFinished` event it checks whether it should close the auction.
For the close to happen the replica must be the one designated to close and all replicas must have
reported that they have finished. 



