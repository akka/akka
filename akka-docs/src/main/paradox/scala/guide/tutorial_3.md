# Part 3: Working with Device Actors
In the previous topics we explained how to view actor systems _in the large_, that is, how components should be represented, how actors should be arranged in the hierarchy. In this part, we will look at actors _in the small_ by implementing the device actor.

If we were working with objects, we would typically design the API as _interfaces_, a collection of abstract methods to be filled out by the actual implementation. In the world of actors, protocols take the place of interfaces. While it is not possible to formalize general protocols in the programming language, we can compose their most basic element, messages. So, we will start by identifying the messages we will want to send to device actors.

Typically, messages fall into categories, or patterns. By identifying these patterns, you will find that it becomes easier to choose between them and to implement them. The first example demonstrates the _request-respond_ message pattern.

## Identifying messages for devices
The tasks of a device actor will be simple:

 * Collect temperature measurements
 * When asked, report the last measured temperature

However, a device might start without immediately having a temperature measurement. Hence, we need to account for the case where a temperature is not present. This also allows us to test the query part of the actor without the write part present, as the device actor can simply report an empty result.

The protocol for obtaining the current temperature from the device actor is simple. The actor:

 1. Waits for a request for the current temperature.
 2. Responds to the request with a reply that either:

    * contains the current temperature or,
    * indicates that a temperature is not yet available.

We need two messages, one for the request, and one for the reply. Our first attempt might look like the following:

Scala
:   @@snip [DeviceInProgress.scala]($code$/scala/tutorial_3/DeviceInProgress.scala) { #read-protocol-1 }

Java
:   @@snip [DeviceInProgress.java]($code$/java/jdocs/tutorial_3/DeviceInProgress.java) { #read-protocol-1 }

These two messages seem to cover the required functionality. However, the approach we choose must take into account the distributed nature of the application. While the basic mechanism is the same for communicating with an actor on the local JVM as with a remote actor, we need to keep the following in mind:

* There will be observable differences in the latency of delivery between local and remote messages, because factors like network link bandwidth and the message size also come into play.
* Reliability is a concern because a remote message send involves more steps, which means that more can go wrong.
* A local send will just pass a reference to the message inside the same JVM, without any restrictions on the underlying object which is sent, whereas a remote transport will place a limit on the message size.

In addition, while sending inside the same JVM is significantly more reliable, if an
actor fails due to a programmer error while processing the message, the effect is basically the same as if a remote network request fails due to the remote host crashing while processing the message. Even though in both cases, the service recovers after a while (the actor is restarted by its supervisor, the host is restarted by an operator or by a monitoring system) individual requests are lost during the crash. **Therefore, writing your actors such that every
message could possibly be lost is the safe, pessimistic bet.**

But to further understand the need for flexibility in the protocol, it will help to consider Akka message ordering and message delivery guarantees. Akka provides the following behavior for message sends:

 * At-most-once delivery, that is, no guaranteed delivery.
 * Message ordering is maintained per sender, receiver pair.

The following sections discuss this behavior in more detail:

* [Message delivery](#message-delivery)
* [Message ordering](#message-ordering)

### Message delivery
The delivery semantics provided by messaging subsystems typically fall into the following categories:

 * **At-most-once delivery** &#8212; each message is delivered zero or one time; in more causal terms it means that messages can be lost, but are never duplicated.
 * **At-least-once delivery** &#8212; potentially multiple attempts are made to deliver each message, until at least one succeeds; again, in more causal terms this means that messages can be duplicated but are never lost.
 * **Exactly-once delivery** &#8212; each message is delivered exactly once to the recipient; the message can neither be lost nor be duplicated.

The first behavior, the one used by Akka, is the cheapest and results in the highest performance. It has the least implementation overhead because it can be done in a fire-and-forget fashion without keeping the state at the sending end or in the transport mechanism. The second, at-least-once, requires retries to counter transport losses. This adds the overhead of keeping the state at the sending end and having an acknowledgment mechanism at the receiving end. Exactly-once delivery is most expensive, and results in the worst performance: in addition to the overhead added by at-least-once delivery, it requires the state to be kept at the receiving end in order to filter out
duplicate deliveries.

In an actor system, we need to determine exact meaning of a guarantee &#8212; at which point does the system consider the delivery as accomplished:

 1. When the message is sent out on the network?
 2. When the message is received by the target actor's host?
 3. When the message is put into the target actor's mailbox?
 4. When the message target actor starts to process the message?
 5. When the target actor has successfully processed the message?

Most frameworks and protocols that claim guaranteed delivery actually provide something similar to points 4 and 5. While this sounds reasonable, **is it actually useful?** To understand the implications, consider a simple, practical example: a user attempts to place an order and we only want to claim that it has successfully processed once it is actually on disk in the orders database.

If we rely on the successful processing of the message, the actor will report success as soon as the order has been submitted to the internal API that has the responsibility to validate it, process it and put it into the database. Unfortunately,
immediately after the API has been invoked any the following can happen:

 * The host can crash.
 * Deserialization can fail.
 * Validation can fail.
 * The database might be unavailable.
 * A programming error might occur.

This illustrates that the **guarantee of delivery** does not translate to the **domain level guarantee**. We only want to report success once the order has been actually fully processed and persisted. **The only entity that can report success is the application itself, since only it has any understanding of the domain guarantees required. No generalized framework can figure out the specifics of a particular domain and what is considered a success in that domain**.

In this particular example, we only want to signal success after a successful database write, where the database acknowledged that the order is now safely stored. **For these reasons Akka lifts the responsibilities of guarantees to the application
itself, i.e. you have to implement them yourself. This gives you full control of the guarantees that you want to provide**. Now, let's consider the message ordering that Akka provides to make it easy to reason about application logic.

### Message Ordering

In Akka, for a given pair of actors, messages sent directly from the first to the second will not be received out-of-order. The word directly emphasizes that this guarantee only applies when sending with the tell operator directly to the final destination, but not when employing mediators.

If:

 * Actor `A1` sends messages `M1`, `M2`, `M3` to `A2`.
 * Actor `A3` sends messages `M4`, `M5`, `M6` to `A2`.

This means that, for Akka messages:

 * If `M1` is delivered it must be delivered before `M2` and `M3`.
 * If `M2` is delivered it must be delivered before `M3`.
 * If `M4` is delivered it must be delivered before `M5` and `M6`.
 * If `M5` is delivered it must be delivered before `M6`.
 * `A2` can see messages from `A1` interleaved with messages from `A3`.
 * Since there is no guaranteed delivery, any of the messages may be dropped, i.e. not arrive at `A2`.

For the full details on delivery guarantees please refer to the @ref[reference page](../general/message-delivery-reliability.md).

Reviewers: I wasn't sure if the "This means that" list of bullets *is* the ordering that Akka provides? I've edited to make it read that way. And, I think a diagram here would be really helpful, as well as a summary of the benefits (again).

## Adding flexibility to device messages

Our first query protocol was correct, but did not take into account distributed application execution. If we want to implement resends in the actor that queries a device actor (because of timed out requests), or if we want to query multiple actors, we need to be able to correlate requests and responses. Hence, we add one more field to our messages, so that an ID can be provided by the requester.

You can now add the following message definitions to your `IotApp` source file:

Scala
:   @@snip [DeviceInProgress.scala]($code$/scala/tutorial_3/DeviceInProgress.scala) { #read-protocol-2 }

Java
:   @@snip [DeviceInProgress2.java]($code$/java/jdocs/tutorial_3/inprogress2/DeviceInProgress2.java) { #read-protocol-2 }

## Defining the device actor and its read protocol

As we learned in the Hello World example, each actor defines the type of messages it will accept. Our device actor has the responsibility to use the same ID parameter for the response of a given query, which would make it look like the following (we will add this code to our app in a later step).

Scala
:   @@snip [DeviceInProgress.scala]($code$/scala/tutorial_3/DeviceInProgress.scala) { #device-with-read }

Java
:   @@snip [DeviceInProgress2.java]($code$/java/jdocs/tutorial_3/inprogress2/DeviceInProgress2.java) { #device-with-read }

Note in the code that:

* The helper object defines how to construct a `Device` actor. The `props` parameters include an ID for the device and the group to which it belongs, which we will use later.
* The helper object includes the definitions of the messages we reasoned about previously.
* In the `Device` class, the value of `lastTemperatureReading` is initially set to @scala[`None`]@java[`Optional.empty()`], and the actor will simply report it back if queried.

## Testing the actor

Based on the simple actor above, we could write a simple test. In the `\test\scala\com\lightbend\akka\sample` directory of your project, add the following code to a `DeviceSpec.scala` file.
@scala[(We use ScalaTest but any other test framework can be used with the Akka Testkit)].

You can run this test @java[by running `mvn test` or] by running `test` at the sbt prompt.

Scala
:   @@snip [DeviceSpec.scala]($code$/scala/tutorial_3/DeviceSpec.scala) { #device-read-test }

Java
:   @@snip [DeviceTest.java]($code$/java/jdocs/tutorial_3/DeviceTest.java) { #device-read-test }

Now, the actor needs a way to change the state of the temperature when it receives a message from the sensor.

## Adding a write protocol

The purpose of the write protocol is to update the `currentTemperature` field when the actor receives a message that contains the temperature. Again, it is tempting to define the write protocol as a very simple message, something like this:

Scala
:   @@snip [DeviceInProgress.scala]($code$/scala/tutorial_3/DeviceInProgress.scala) { #write-protocol-1 }

Java
:   @@snip [DeviceInProgress3.java]($code$/java/jdocs/tutorial_3/DeviceInProgress3.java) { #write-protocol-1 }

However, this approach does not take into account that the sender of the record temperature message can never be sure if the message was processed or not. We have seen that Akka does not guarantee delivery of these messages and leaves it to the application to provide success notifications. In our case, we would like to send an acknowledgment to the sender once we have updated our last temperature recording, e.g. @scala[`final case class TemperatureRecorded(requestId: Long)`] @java[`TemperatureRecorded`].
Just like in the case of temperature queries and responses, it is a good idea to include an ID field to provide maximum flexibility.

## Actor with read and write messages

Putting the read and write protocol together, the device actor looks like the following example. You can add this to you `IotApp` source file.

Scala
:  @@snip [Device.scala]($code$/scala/tutorial_3/Device.scala) { #full-device }

Java
:  @@snip [Device.java]($code$/java/jdocs/tutorial_3/Device.java) { #full-device }

We should also write a new test case now, exercising both the read/query and write/record functionality together:

Scala:
:   @@snip [DeviceSpec.scala]($code$/scala/tutorial_3/DeviceSpec.scala) { #device-write-read-test }

Java:
:   @@snip [DeviceTest.java]($code$/java/jdocs/tutorial_3/DeviceTest.java) { #device-write-read-test }

## What's Next?

So far, we have started designing our overall architecture, and we wrote the first actor that directly corresponds to the domain. We now have to create the component that is responsible for maintaining groups of devices and the device actors themselves.
