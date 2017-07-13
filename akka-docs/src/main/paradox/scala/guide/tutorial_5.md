# Part 5: Querying Device Groups

The conversational patterns that we have seen so far are simple in the sense that they require the actor to keep little or no state. Specifically:

* Device actors return a reading, which requires no state change
* Record a temperature, which updates a single field
* Device Group actors maintain group membership by simply adding or removing entries from a map

In this part, we will use a more complex example. Since homeowners will be interested in the temperatures throughout their home, our goal is to be able to query all of the device actors in a group. Let us start by investigating how such a query API should behave.

## Dealing with possible scenarios
The very first issue we face is that the membership of a group is dynamic. Each sensor device is represented by an actor that can stop at any time. At the beginning of the query, we can ask all of the existing device actors for the current temperature. However, during the lifecycle of the query:

 * A device actor might stop and not be able to respond back with a temperature reading.
 * A new device actor might start up and not be included in the query because we weren't aware of it.

These issues can be addressed in many different ways, but the important point is to settle on the desired behavior. The following works well for our use case:

 * When a query arrives, the group actor takes a _snapshot_ of the existing device actors and will only ask those actors for the temperature.
 * Actors that start up _after_ the query arrives are simply ignored.
 * If an actor in the snapshot stops during the query without answering, we will simply report the fact that it stopped to the sender of the query message.

Apart from device actors coming and going dynamically, some actors might take a long time to answer. For example, they could be stuck in an accidental infinite loop, or fail due to a bug and drop our request. We don't want the query to continue indefinitely, so we will consider it complete in either of the following cases:

* All actors in the snapshot have either responded or have confirmed being stopped.
* We reach a pre-defined deadline.

Given these decisions, along with the fact that a device in the snapshot might have just started and not yet received a temperature to record, we can define four states
for each device actor, with respect to a temperature query:

 * It has a temperature available: @scala[`Temperature(value)`] @java[`Temperature`].
 * It has responded, but has no temperature available yet: `TemperatureNotAvailable`.
 * It has stopped before answering: `DeviceNotAvailable`.
 * It did not respond before the deadline: `DeviceTimedOut`.

Summarizing these in message types we can add the following to `DeviceGroup`:

Scala
:   @@snip [DeviceGroup.scala]($code$/scala/tutorial_5/DeviceGroup.scala) { #query-protocol }

Java
:   @@snip [DeviceGroup.java]($code$/java/jdocs/tutorial_5/DeviceGroup.java) { #query-protocol }

## Implementing the query

One approach for implementing the query involves adding code to the group device actor. However, in practice this can be very cumbersome and error prone. Remember that when we start a query, we need to take a snapshot of the devices present and start a timer so that we can enforce the deadline. In the meantime, _another query_ can arrive. For the second query, of course, we need to keep track of the exact same information but in isolation from the previous query. This would require us to maintain separate mappings between queries and device actors.

Instead, we will implement a simpler, and superior approach. We will create an actor that represents a _single query_ and that performs the tasks needed to complete the query on behalf of the group actor. So far we have created actors that belonged to classical domain objects, but now, we will create an
actor that represents a process or a task rather than an entity. We benefit by keeping our group device actor simple and being able to better test query capability in isolation.

### Defining the query actor

First, we need to design the lifecycle of our query actor. This consists of identifying its initial state, the first action it will take, and the cleanup &#8212; if necessary. The query actor will need the following information:

 * The snapshot and IDs of active device actors to query.
 * The ID of the request that started the query (so that we can include it in the reply).
 * The reference of the actor who sent the query. We will send the reply to this actor directly.
 * A deadline that indicates how long the query should wait for replies. Making this a parameter will simplify testing.

#### Scheduling the query timeout
Since we need a way to indicate how long we are willing to wait for responses, it is time to introduce a new Akka feature that we have
not used yet, the built-in scheduler facility. Using the scheduler is simple:

* We get the scheduler from the `ActorSystem`, which, in turn,
is accessible from the actor's context: @scala[`context.system.scheduler`]@java[`getContext().getSystem().scheduler()`]. This needs an @scala[implicit] `ExecutionContext` which
is basically the thread-pool that will execute the timer task itself. In our case, we use the same dispatcher
as the actor by @scala[importing `import context.dispatcher`] @java[passing in `getContext().dispatcher()`].
* The
@scala[`scheduler.scheduleOnce(time, actorRef, message)`] @java[`scheduler.scheduleOnce(time, actorRef, message, executor, sender)`] method will schedule the message `message` into the future by the
specified `time` and send it to the actor `actorRef`.

We need to create a message that represents the query timeout. We create a simple message `CollectionTimeout` without any parameters for this purpose. The return value from `scheduleOnce` is a `Cancellable` which can be used to cancel the timer if the query finishes successfully in time.  At the start of the query, we need to ask each of the device actors for the current temperature. To be able to quickly
detect devices that stopped before they got the `ReadTemperature` message we will also watch each of the actors. This
way, we get `Terminated` messages for those that stop during the lifetime of the query, so we don't need to wait
until the timeout to mark these as not available.

Putting this together, the outline of our `DeviceGroupQuery` actor looks like this:

Scala
:   @@snip [DeviceGroupQuery.scala]($code$/scala/tutorial_5/DeviceGroupQuery.scala) { #query-outline }

Java
:   @@snip [DeviceGroupQuery.java]($code$/java/jdocs/tutorial_5/DeviceGroupQuery.java) { #query-outline }

#### Tracking actor state

The query actor, apart from the pending timer, has one stateful aspect, tracking the set of actors that: have replied, have stopped, or have not replied. One way to track this state is
to create a mutable field in the actor @scala[(a `var`)]. A different approach takes advantage of the ability to change how
an actor responds to messages. A
`Receive` is just a function (or an object, if you like) that can be returned from another function. By default, the `receive` block defines the behavior of the actor, but it is possible to change it multiple times during the life of the actor. We simply call `context.become(newBehavior)`
where `newBehavior` is anything with type `Receive` @scala[(which is just a shorthand for `PartialFunction[Any, Unit]`)].  We will leverage this
feature to track the state of our actor.

For our use case:

1. Instead of defining `receive` directly, we delegate to a `waitingForReplies` function to create the `Receive`.
1. The `waitingForReplies` function will keep track of two changing values:
   * a `Map` of already received replies
   * a `Set` of actors that we still wait on
1. We have three events to act on:
   * We can receive a
`RespondTemperature` message from one of the devices.
   * We can receive a `Terminated` message for a device actor
that has been stopped in the meantime.
   * We can reach the deadline and receive a `CollectionTimeout`.

In the first two cases, we need to keep track of the replies, which we now simply delegate to a method `receivedResponse`, which we will discuss later. In the case of timeout, we need to simply take all the actors that have not yet replied yet (the members of the set `stillWaiting`) and put a `DeviceTimedOut` as the status in the final reply. Then we reply to the submitter of the query with the collected results and stop the query actor.

To accomplish this, add the following to your `DeviceGroupQuery` source file:



Scala
:   @@snip [DeviceGroupQuery.scala]($code$/scala/tutorial_5/DeviceGroupQuery.scala) { #query-state }

Java
:   @@snip [DeviceGroupQuery.java]($code$/java/jdocs/tutorial_5/DeviceGroupQuery.java) { #query-state }

It is not yet clear how we will "mutate" the `answersSoFar` and `stillWaiting` data structures. One important thing to note is that the function `waitingForReplies` **does not handle the messages directly. It returns a `Receive` function that will handle the messages**. This means that if we call `waitingForReplies` again, with different parameters,
then it returns a brand new `Receive` that will use those new parameters.

We have seen how we
can install the initial `Receive` by simply returning it from `receive`. In order to install a new one, to record a
new reply, for example, we need some mechanism. This mechanism is the method `context.become(newReceive)` which will
_change_ the actor's message handling function to the provided `newReceive` function. You can imagine that before
starting, your actor automatically calls `context.become(receive)`, i.e. installing the `Receive` function that
is returned from `receive`. This is another important observation: **it is not `receive` that handles the messages,
it just returns a `Receive` function that will actually handle the messages**.

We now have to figure out what to do in `receivedResponse`. First, we need to record the new result in the map `repliesSoFar` and remove the actor from `stillWaiting`. The next step is to check if there are any remaining actors we are waiting for. If there is none, we send the result of the query to the original requester and stop the query actor. Otherwise, we need to update the `repliesSoFar` and `stillWaiting` structures and wait for more
messages.

In the code before, we treated `Terminated` as the implicit response `DeviceNotAvailable`, so `receivedResponse` does
not need to do anything special. However, there is one small task we still need to do. It is possible that we receive a proper
response from a device actor, but then it stops during the lifetime of the query. We don't want this second event
to overwrite the already received reply. In other words, we don't want to receive `Terminated` after we recorded the
response. This is simple to achieve by calling `context.unwatch(ref)`. This method also ensures that we don't
receive `Terminated` events that are already in the mailbox of the actor. It is also safe to call this multiple times,
only the first call will have any effect, the rest is simply ignored.

With all this knowledge, we can create the `receivedResponse` method:

Scala
:   @@snip [DeviceGroupQuery.scala]($code$/scala/tutorial_5/DeviceGroupQuery.scala) { #query-collect-reply }

Java
:   @@snip [DeviceGroupQuery.java]($code$/java/jdocs/tutorial_5/DeviceGroupQuery.java) { #query-collect-reply }

It is quite natural to ask at this point, what have we gained by using the `context.become()` trick instead of
just making the `repliesSoFar` and `stillWaiting` structures mutable fields of the actor (i.e. `var`s)? In this
simple example, not that much. The value of this style of state keeping becomes more evident when you suddenly have
_more kinds_ of states. Since each state
might have temporary data that is relevant itself, keeping these as fields would pollute the global state
of the actor, i.e. it is unclear what fields are used in what state. Using parameterized `Receive` "factory"
methods we can keep data private that is only relevant to the state. It is still a good exercise to
rewrite the query using @scala[`var`s] @java[mutable fields] instead of `context.become()`. However, it is recommended to get comfortable
with the solution we have used here as it helps structuring more complex actor code in a cleaner and more maintainable way.

Our query actor is now done:

Scala
:   @@snip [DeviceGroupQuery.scala]($code$/scala/tutorial_5/DeviceGroupQuery.scala) { #query-full }

Java
:   @@snip [DeviceGroupQuery.java]($code$/java/jdocs/tutorial_5/DeviceGroupQuery.java) { #query-full }

### Testing the query actor

Now let's verify the correctness of the query actor implementation. There are various scenarios we need to test individually to make
sure everything works as expected. To be able to do this, we need to simulate the device actors somehow to exercise
various normal or failure scenarios. Thankfully we took the list of collaborators (actually a `Map`) as a parameter
to the query actor, so we can easily pass in @scala[`TestProbe`] @java[`TestKit`] references. In our first test, we try out the case when
there are two devices and both report a temperature:

Scala
:   @@snip [DeviceGroupQuerySpec.scala]($code$/scala/tutorial_5/DeviceGroupQuerySpec.scala) { #query-test-normal }

Java
:   @@snip [DeviceGroupQueryTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupQueryTest.java) { #query-test-normal }

That was the happy case, but we know that sometimes devices cannot provide a temperature measurement. This
scenario is just slightly different from the previous:

Scala
:   @@snip [DeviceGroupQuerySpec.scala]($code$/scala/tutorial_5/DeviceGroupQuerySpec.scala) { #query-test-no-reading }

Java
:   @@snip [DeviceGroupQueryTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupQueryTest.java) { #query-test-no-reading }

We also know, that sometimes device actors stop before answering:

Scala
:   @@snip [DeviceGroupQuerySpec.scala]($code$/scala/tutorial_5/DeviceGroupQuerySpec.scala) { #query-test-stopped }

Java
:   @@snip [DeviceGroupQueryTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupQueryTest.java) { #query-test-stopped }

If you remember, there is another case related to device actors stopping. It is possible that we get a normal reply
from a device actor, but then receive a `Terminated` for the same actor later. In this case, we would like to keep
the first reply and not mark the device as `DeviceNotAvailable`. We should test this, too:

Scala
:   @@snip [DeviceGroupQuerySpec.scala]($code$/scala/tutorial_5/DeviceGroupQuerySpec.scala) { #query-test-stopped-later }

Java
:   @@snip [DeviceGroupQueryTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupQueryTest.java) { #query-test-stopped-later }

The final case is when not all devices respond in time. To keep our test relatively fast, we will construct the
`DeviceGroupQuery` actor with a smaller timeout:

Scala
:   @@snip [DeviceGroupQuerySpec.scala]($code$/scala/tutorial_5/DeviceGroupQuerySpec.scala) { #query-test-timeout }

Java
:   @@snip [DeviceGroupQueryTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupQueryTest.java) { #query-test-timeout }

Our query works as expected now, it is time to include this new functionality in the `DeviceGroup` actor now.

## Adding query capability to the group

Including the query feature in the group actor is fairly simple now. We did all the heavy lifting in the query actor
itself, the group actor only needs to create it with the right initial parameters and nothing else.

Scala
:   @@snip [DeviceGroup.scala]($code$/scala/tutorial_5/DeviceGroup.scala) { #query-added }

Java
:   @@snip [DeviceGroup.java]($code$/java/jdocs/tutorial_5/DeviceGroup.java) { #query-added }

It is probably worth restating what we said at the beginning of the chapter. By keeping the temporary state that is only relevant to the query itself in a separate actor we keep the group actor implementation very simple. It delegates
everything to child actors and therefore does not have to keep state that is not relevant to its core business. Also, multiple queries can now run parallel to each other, in fact, as many as needed. In our case querying an individual device actor is a fast operation, but if this were not the case, for example, because the remote sensors need to be contacted over the network, this design would significantly improve throughput.

We close this chapter by testing that everything works together. This test is just a variant of the previous ones, now exercising the group query feature:

Scala
:   @@snip [DeviceGroupSpec.scala]($code$/scala/tutorial_5/DeviceGroupSpec.scala) { #group-query-integration-test }

Java
:   @@snip [DeviceGroupTest.java]($code$/java/jdocs/tutorial_5/DeviceGroupTest.java) { #group-query-integration-test }

## Summary
In the context of the IoT system, this guide introduced the following concepts, among others. You can follow the links to review them if necessary:

* @ref[The hierarchy of actors and their lifecycle](tutorial_1.md)
* @ref[The importance of designing messages for flexibility](tutorial_3.md)
* @ref[How to watch and stop actors, if necessary](tutorial_4.md#keeping-track-of-the-device-actors-in-the-group)

## What's Next?

To continue your journey with Akka, we recommend:

* Start building your own applications with Akka, make sure you [get involved in our amazing community](http://akka.io/get-involved) for help if you get stuck.
* If you’d like some additional background, read the rest of the reference documentation and check out some of the @ref[books and video’s](../additional/books.md) on Akka.
