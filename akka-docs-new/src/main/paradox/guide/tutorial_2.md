# Your second Akka application, part 2: The Device actor

In part 1 we explained how to view actor systems _in the large_, i.e. how components should be represented, how
actor should be arranged in the hierarchy. In this part we will look at actors _in the small_ by implementing an
actor with the most common conversational patterns.
 
In particular, leaving the components aside for a while, we will implement the actor that represents a device. The 
tasks of this actor will be rather simple:

 * Collect temperature measurements
 * Report the last measured temperature if asked
 
When working with objects we usually design our API as _interfaces_, which are basically a collection of abstract
methods to be filled in by the actual implementation. In the world of actors, the counterpart of interfaces are
protocols. While it is not possible to formalize general protocols in the programming language, we can formalize
its most basic elements: the messages.

## The query protocol

Just because a device have been started it does not mean that it has immediately a temperature measurement. Hence, we
need to account for the case that a temperature is not present in our protocol. This fortunately means that we
can test the query part of the actor without the write part present, as it can simply report an empty result.
 
The protocol for obtaining the current temperature from the device actor is rather simple looking:

 1. Wait for a request for current temperature
 2. respond to the request with a reply containing the current temperature or an indication that it is not yet
    available
    
We need two messages, one for the requests, and one for the replies. A first attempt could look like this:

@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceInProgress.scala) { #read-protocol-1 }

This is a fine approach, but it limits the flexibility of the protocol. To understand why, we need to talk
about message ordering and message delivery guarantees in general.

## Message ordering, delivery guarantees

In order to give some context to the discussion below, consider an application which spans multiple network hosts. 
The basic mechanism for communication is the same whether sending to an actor on the local JVM or to a remote actor,
but of course there will be observable differences in the latency of delivery (possibly also depending on the bandwidth 
of the network link and the message size) and the reliability. In case of a remote message send there are obviously 
more steps involved which means that more can go wrong. Another aspect is that local sending will just pass a 
reference to the message inside the same JVM, without any restrictions on the underlying object which is sent, 
whereas a remote transport will place a limit on the message size.

It is also important to keep in mind, that while passing inside the same JVM is significantly more reliable, if an
actor fails due to a programmer error while processing the message, the effect is basically the same as if a remote,
network request fails due to the remote host crashing while processing the message. Even though in both cases the
service is recovered after a while (the actor is restarted by its supervisor, the host is restarted by an operator
or by a monitoring system) individual requests are lost during the crash. **Writing your actors such that every 
message could possibly be lost is the safe, pessimistic bet.**
 
These are the rules in Akka for message sends:

 * at-most-once delivery, i.e. no guaranteed delivery
 * message ordering is maintained per sender, receiver pair
 
### What does "at-most-once" mean?

When it comes to describing the semantics of a delivery mechanism, there are three basic categories:

 * **at-most-once delivery** means that for each message handed to the mechanism, that message is delivered zero or 
   one times; in more casual terms it means that messages may be lost, but never duplicated.
 * **at-least-once delivery** means that for each message handed to the mechanism potentially multiple attempts are made 
   at delivering it, such that at least one succeeds; again, in more casual terms this means that messages may be 
   duplicated but not lost.
 * **exactly-once delivery** means that for each message handed to the mechanism exactly one delivery is made to 
   the recipient; the message can neither be lost nor duplicated.

The first one is the cheapest, highest performance, least implementation overhead because it can be done in a 
fire-and-forget fashion without keeping state at the sending end or in the transport mechanism. 
The second one requires retries to counter transport losses, which means keeping state at the sending end and 
having an acknowledgement mechanism at the receiving end. The third is most expensive, and has consequently worst 
performance: in addition to the second it requires state to be kept at the receiving end in order to filter out 
duplicate deliveries.

### Why No Guaranteed Delivery?

At the core of the problem lies the question what exactly this guarantee shall mean, i.e. at which point does
the delivery considered to be guaranteed:

 1. The message is sent out on the network?
 2. The message is received by the other host?
 3. The message is put into the target actor's mailbox?
 4. The message is starting to be processed by the target actor?
 5. The message is processed successfully by the target actor?
 
Most frameworks/protocols claiming guaranteed delivery actually provide something similar to point 4 and 5. While this
sounds fair, **is this actually useful?** To understand the implications, consider a simple, practical example:
a user attempts to place an order and we only want to claim that it has successfully processed once it is actually on
disk in the database containing orders.

If we rely on the guarantees of such system it will report success as soon as the order has been submitted to the
internal API that has the responsibility to validate it, process it and put it into the database. Unfortunately, 
immediately after the API has been invoked

 * the host can immediately crash
 * deserialization can fail
 * validation can fail
 * the database might be unavailable
 * a programming error might occur
 
The problem is that the **guarantee of delivery** does not translate to the **domain level guarantee**. We only want to
report success once the order has been actually fully processed and persisted. **The only entity that can report
success is the application itself, since only it has any understanding of the domain guarantees required. No generalized
framework can figure out the specifics of a particular domain and what is considered a success in that domain**. In
this particular example, we only want to signal success after a successful database write, where the database acknowledged
that the order is now safely stored. **For these reasons Akka lifts the responsibilities of guarantees to the application
itself, i.e. you have to implement them yourself. On the other hand, you are in full control of the guarantees that you want
to provide**.

### Message ordering

The rule is that for a given pair of actors, messages sent directly from the first to the second will not be 
received out-of-order. The word directly emphasizes that this guarantee only applies when sending with the tell 
operator directly to the final destination, but not when employing mediators.

If

 * Actor `A1` sends messages `M1`, `M2`, `M3` to `A2`
 * Actor `A3` sends messages `M4`, `M5`, `M6` to `A2`
 
This means that:

 * If `M1` is delivered it must be delivered before `M2` and `M3`
 * If `M2` is delivered it must be delivered before `M3`
 * If `M4` is delivered it must be delivered before `M5` and `M6`
 * If `M5` is delivered it must be delivered before `M6`
 * `A2` can see messages from `A1` interleaved with messages from `A3`
 * Since there is no guaranteed delivery, any of the messages may be dropped, i.e. not arrive at `A2`

For the full details on delivery guarantees please refer to the reference page (TODO reference).

### Revisiting the query protocol

There is nothing wrong with our first query protocol but it limits our flexibility. If we want to implement resends
in the actor that queries our device actor (because of timed out requests) or want to query multiple actors it
can be helpful to put an additional query ID field in the message which helps us correlate requests with responses.

Hence, we add one more field to our messages, so that an ID can be provided by the requester:

@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceInProgress.scala) { #read-protocol-2 }

Our device actor has the responsibility to use the same ID for the response of a given query. Now we can sketch
our device actor:

@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceInProgress.scala) { #device-with-read }

We maintain the current temperature (which can be empty initially), and we simply report it back if queried. We also
added fields for the ID of the device and the group it belongs to, which we will use later. 


We can already write a simple test for this functionality (we use ScalaTest but any other test framework can be
used with the Akka Testkit):

@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceSpec.scala) { #device-read-test }

## The write protocol

As a first attempt, we could model recording the current temperature in the device actor as a single message:
 
 * When a temperature record request is received, update the `currentTemperature` field.
  
Such a message could possibly look like this:
  
@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceInProgress.scala) { #write-protocol-1 }
  
The problem with this approach is that the sender of the record temperature message can never be sure if the message
was processed or not. We have seen that Akka does not guarantee delivery of these messages and leaves it to the 
application to provide success notifications. In our case we would like to send an acknowledgement to the sender
once we have updated our last temperature recording. Just like in the case of temperature queries and responses, it
is a good idea to include an ID field in this case, too, to provide maximum flexibility. 

Putting these together, the device actor will look like this:

@@snip [Hello.scala](../../../test/scala/tutorial_2/Device.scala) { #full-device }

We are also responsible for writing a new test case now, exercising both the read/query and write/record functionality
together:

@@snip [Hello.scala](../../../test/scala/tutorial_2/DeviceSpec.scala) { #device-write-read-test }

## What is next?

So far, we have started designing our overall architecture, and we wrote our first actor directly corresponding to the
domain. We now have to create the component that is responsible for maintaining groups of devices and the device
actors themselves.