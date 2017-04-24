# Your Second Akka Application, Part 4: Querying a Group of Devices

The conversational patterns we have seen so far were simple in the sense that they required no or little state to be kept in the
actor that is only relevant to the conversation. Our device actors either simply returned a reading, which required no
state change, recorded a temperature, which was required an update of a single field, or in the most complex case,
managing groups and devices, we had to add or remove simple entries from a map.

In this chapter, we will see a more complex example. Our goal is to add a new service to the group device actor, one which
allows querying the temperature from all running devices. Let us start by investigating how we want our query API to
behave.

The very first issue we face is that the set of devices is dynamic, and each device is represented by an actor that
can stop at any time. At the beginning of the query, we need to ask all of the device actors for the current temperature
that we know about. However, during the lifecycle of the query:

 * A device actor may stop and not respond back with a temperature reading.
 * A new device actor might start up, but we missed asking it for the current temperature.

There are many approaches that can be taken to address these issues, but the important point is to settle on what is
the desired behavior. We will pick the following two guarantees:

 * When a query arrives at the group, the group actor takes a _snapshot_ of the existing device actors and will only
   ask those for the temperature. Actors that are started _after_ the arrival of the query are simply ignored.
 * When an actor stops during the query without answering (i.e. before all the actors we asked for the temperature
   responded) we simply report back that fact to the sender of the query message.

Apart from device actors coming and going dynamically, some actors might take a long time to answer, for example, because
they are stuck in an accidental infinite loop, or because they failed due to a bug and dropped our request. Ideally,
we would like to give a deadline to our query:

 * The query is considered completed if either all actors have responded (or confirmed being stopped), or we reach
   the deadline.

Given these decisions, and the fact that a device might not have a temperature to record, we can define four states
that each device can be in, according to the query:

 * It has a temperature available: `Temperature(value)`.
 * It has responded, but has no temperature available yet: `TemperatureNotAvailable`.
 * It has stopped before answering: `DeviceNotAvailable`.
 * It did not respond before the deadline: `DeviceTimedOut`.

Summarizing these in message types we can add the following to `DeviceGroup`:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroup.scala) { #query-protocol }

## Implementing the Query

One of the approaches for implementing the query could be to add more code to the group device actor. While this is
possible, in practice this can be very cumbersome and error prone. When we start a query, we need to take a snapshot
of the devices present at the start of the query and start a timer so that we can enforce the deadline. Unfortunately,
during the time we execute a query _another query_ might just arrive. For this other query, of course, we need to keep
track of the exact same information but isolated from the previous query. This complicates the code and also poses
some problems. For example, we would need a data structure that maps the `ActorRef`s of the devices to the queries
that use that device, so that they can be notified when such a device terminates, i.e. a `Terminated` message is
received.

There is a much simpler approach that is superior in every way, and it is the one we will implement. We will create
an actor that represents a _single query_ and which performs the tasks needed to complete the query on behalf of the
group actor. So far we have created actors that belonged to classical domain objects, but now, we will create an
actor that represents a process or task rather than an entity. This move keeps our group device actor simple and gives
us better ways to test the query capability in isolation.

First, we need to design the lifecycle of our query actor. This consists of identifying its initial state, then
the first action to be taken by the actor, then, the cleanup if necessary. There are a few things the query should
need to be able to work:

 * The snapshot of active device actors to query, and their IDs.
 * The requestID of the request that started the query (so we can include it in the reply).
 * The `ActorRef` of the actor who sent the group actor the query. We will send the reply to this actor directly.
 * A timeout parameter, how long the query should wait for replies. Keeping this as a parameter will simplify testing.

Since we need to have a timeout for how long we are willing to wait for responses, it is time to introduce a new feature that we have
not used yet: timers. Akka has a built-in scheduler facility for this exact purpose. Using it is simple, the
`scheduler.scheduleOnce(time, actorRef, message)` method will schedule the message `message` into the future by the
specified `time` and send it to the actor `actorRef`. To implement our query timeout we need to create a message
that represents the query timeout. We create a simple message `CollectionTimeout` without any parameters for
this purpose. The return value from `scheduleOnce` is a `Cancellable` which can be used to cancel the timer
if the query finishes successfully in time. Getting the scheduler is possible from the `ActorSystem`, which, in turn,
is accessible from the actor's context: `context.system.scheduler`. This needs an implicit `ExecutionContext` which
is basically the thread-pool that will execute the timer task itself. In our case, we use the same dispatcher
as the actor by importing `import context.dispatcher`.

At the start of the query, we need to ask each of the device actors for the current temperature. To be able to quickly
detect devices that stopped before they got the `ReadTemperature` message we will also watch each of the actors. This
way, we get `Terminated` messages for those that stop during the lifetime of the query, so we don't need to wait
until the timeout to mark these as not available.

Putting together all these, the outline of our actor looks like this:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuery.scala) { #query-outline }

The query actor, apart from the pending timer, has one stateful aspect about it: the actors that did not answer so far or,
from the other way around, the set of actors that have replied or stopped. One way to track this state is
to create a mutable field in the actor (a `var`). There is another approach. It is also possible to change how
the actor responds to messages. By default, the `receive` block defines the behavior of the actor, but it is possible
to change it, several times, during the life of the actor. This is possible by calling `context.become(newBehavior)`
where `newBehavior` is anything with type `Receive` (which is just a shorthand for `PartialFunction[Any, Unit]`). A
`Receive` is just a function (or an object, if you like) that can be returned from another function. We will leverage this
feature to track the state of our actor.

As the first step, instead of defining `receive` directly, we delegate to another function to create the `Receive`, which
we will call `waitingForReplies`. This will keep track of two changing values, a `Map` of already received replies
and a `Set` of actors that we still wait on. We have three events that we should act on. We can receive a
`RespondTemperature` message from one of the devices. Second, we can receive a `Terminated` message for a device actor
that has been stopped in the meantime. Finally, we can reach the deadline and receive a `CollectionTimeout`. In the
first two cases, we need to keep track of the replies, which we now simply delegate to a method `receivedResponse` which
we will discuss later. In the case of timeout, we need to simply take all the actors that have not yet replied yet
(the members of the set `stillWaiting`) and put a `DeviceTimedOut` as the status in the final reply. Then we
reply to the submitter of the query with the collected results and stop the query actor:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuery.scala) { #query-state }

What is not yet clear, how we will "mutate" the `answersSoFar` and `stillWaiting` data structures. One important
thing to note is that the function `waitingForReplies` **does not handle the messages directly. It returns a `Receive`
function that will handle the messages**. This means that if we call `waitingForReplies` again, with different parameters,
then it returns a brand new `Receive` that will use those new parameters. We have seen how we
can install the initial `Receive` by simply returning it from `receive`. In order to install a new one, to record a
new reply, for example, we need some mechanism. This mechanism is the method `context.become(newReceive)` which will
_change_ the actor's message handling function to the provided `newReceive` function. You can imagine that before
starting, your actor automatically calls `context.become(receive)`, i.e. installing the `Receive` function that
is returned from `receive`. This is another important observation: **it is not `receive` that handles the messages,
it just returns a `Receive` function that will actually handle the messages**.

We now have to figure out what to do in `receivedResponse`. First, we need to record the new result in the map
`repliesSoFar` and remove the actor from `stillWaiting`. The next step is to check if there are any remaining actors
we are waiting for. If there is none, we send the result of the query to the original requester and stop
the query actor. Otherwise, we need to update the `repliesSoFar` and `stillWaiting` structures and wait for more
messages.

In the code before, we treated `Terminated` as the implicit response `DeviceNotAvailable`, so `receivedResponse` does
not need to do anything special. However, there is one small task we still need to do. It is possible that we receive a proper
response from a device actor, but then it stops during the lifetime of the query. We don't want this second event
to overwrite the already received reply. In other words, we don't want to receive `Terminated` after we recorded the
response. This is simple to achieve by calling `context.unwatch(ref)`. This method also ensures that we don't
receive `Terminated` events that are already in the mailbox of the actor. It is also safe to call this multiple times,
only the first call will have any effect, the rest is simply ignored.

With all this knowledge, we can create the `receivedResponse` method:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuery.scala) { #query-collect-reply }

It is quite natural to ask at this point, what have we gained by using the `context.become()` trick instead of
just making the `repliesSoFar` and `stillWaiting` structures mutable fields of the actor (i.e. `var`s)? In this
simple example, not that much. The value of this style of state keeping becomes more evident when you suddenly have
_more kinds_ of states. Since each state
might have temporary data that is relevant itself, keeping these as fields would pollute the global state
of the actor, i.e. it is unclear what fields are used in what state. Using parameterized `Receive` "factory"
methods we can keep data private that is only relevant to the state. It is still a good exercise to
rewrite the query using `var`s instead of `context.become()`. However, it is recommended to get comfortable
with the solution we have used here as it helps structuring more complex actor code in a cleaner and more maintainable way.

Or query actor is now done:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuery.scala) { #query-full }

## Testing

Now let's verify the correctness of the query actor implementation. There are various scenarios we need to test individually to make
sure everything works as expected. To be able to do this, we need to simulate the device actors somehow to exercise
various normal or failure scenarios. Thankfully we took the list of collaborators (actually a `Map`) as a parameter
to the query actor, so we can easily pass in `TestProbe` references. In our first test, we try out the case when
there are two devices and both report a temperature:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuerySpec.scala) { #query-test-normal }

That was the happy case, but we know that sometimes devices cannot provide a temperature measurement. This
scenario is just slightly different from the previous:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuerySpec.scala) { #query-test-no-reading }

We also know, that sometimes device actors stop before answering:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuerySpec.scala) { #query-test-stopped }

If you remember, there is another case related to device actors stopping. It is possible that we get a normal reply
from a device actor, but then receive a `Terminated` for the same actor later. In this case, we would like to keep
the first reply and not mark the device as `DeviceNotAvailable`. We should test this, too:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuerySpec.scala) { #query-test-stopped-later }

The final case is when not all devices respond in time. To keep our test relatively fast, we will construct the
`DeviceGroupQuery` actor with a smaller timeout:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupQuerySpec.scala) { #query-test-timeout }

Our query works as expected now, it is time to include this new functionality in the `DeviceGroup` actor now.

## Adding the Query Capability to the Group

Including the query feature in the group actor is fairly simple now. We did all the heavy lifting in the query actor
itself, the group actor only needs to create it with the right initial parameters and nothing else.

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroup.scala) { #query-added }

It is probably worth to reiterate what we said at the beginning of the chapter. By keeping the temporary state
that is only relevant to the query itself in a separate actor we keep the group actor implementation very simple. It delegates
everything to child actors and therefore does not have to keep state that is not relevant to its core business. Also, multiple queries can
now run parallel to each other, in fact, as many as needed. In our case querying an individual device actor is a fast operation, but
if this were not the case, for example, because the remote sensors need to be contacted over the network, this design
would significantly improve throughput.  

We close this chapter by testing that everything works together. This test is just a variant of the previous ones,
now exercising the group query feature:

@@snip [Hello.scala](../../../test/scala/tutorial_4/DeviceGroupSpec.scala) { #group-query-integration-test }
