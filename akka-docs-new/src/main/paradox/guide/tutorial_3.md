# Your second Akka application, part 3: Device groups

In this chapter we will integrate our device actors into a component that manages devices. When a new device comes
on-line, there is no actor representing it. We need to be able to ask the device manager component to create a new
device actor for us if necessary, in the required group (or return a reference to an already existing one).

Since we keep our tutorial system to the bare minimum, we have no actual component that interfaces with the external
world via some networking protocol. For our exercise we will just create the API necessary to integrate with such
a component in the future. In a final system, the steps for connecting a device would look like this: 

 1. The device connects through some protocol to our system 
 2. The component managing network connections accepts the connection
 3. The ID of the device and the ID of the group that it belongs is acquired 
 4. The device manager component is asked to create a group and device actor for the given IDs (or return an existing 
    one)
 5. The device actor (just been created or located) responds with an acknowledgement, at the same time exposing its
    ActorRef directly (by being the sender of the acknowledgement)
 6. The networking component now uses the ActorRef of the device directly, avoiding going through the component
 
We are only concerned with steps 4 and 5 now. We will model the device manager component as an actor tree with three
levels:

DEVICE_MANAGER_TREE_DIAGRAM

 * The top level is the supervisor actor representing the component. It is also the entry point to look up or create
   group and device actors
 * Group actors are supervisors of the devices belonging to the group. Groups both supervise the device actors and
   also provide extra services, like querying the temperature readings from all the devices available
 * Device actors manage all the interactions with the actual devices, storing temperature readings for example
  
When designing actor systems one of the main problems is to decide on the granularity of the actors. For example, it
would be perfectly possible to have only a single actor maintaining all the groups, and devices in `HashMap`s for 
example. It would be also reasonable to keep the groups as separate actors, but keep device state simply inside
the group actor.

We chose this three-level architecture for the following reasons:

 * Having groups as separate actors 
   * allows us to isolate failures happening in a group. If a programmer error would
     happen in the single actor that keeps all state, it would be all wiped out once that actor is restarted affecting
     groups that are otherwise non-faulty.
   * simplifies the problem of querying all the devices belonging to a group (since it only contains state related 
     to the given group) 
   * increases the parallelism of the system by allowing to query multiple groups concurrently. Since groups have
     dedicated actors, all of them can run concurrently.
 * Having devices as separate actors
   * allows us to isolate failures happening in a device actor from the rest of the devices
   * increases the parallelism of collecting temperature readings as actual network connections from different devices
     can talk to the individual device actors directly, reducing contention points. 
     
In practice, this system can be organized in different ways, all dependent on the characteristics of the interactions
between actors. 

The following guidelines help to arrive at the right granularity

 * Prefer larger granularity to smaller. Introducing more fine-grained actors than needed causes more problems than
   it solves
 * Prefer finer granularity if it enables higher concurrency in the system
 * Prefer finer granularity if actors need to handle complex conversations with other actors and hence have many
   states. We will see a very good example for this in the next chapter.
 * Prefer finer granularity if there is too many state to keep around in one place compared to dividing into smaller
   actors.
 * Prefer finer granularity if the current actor has multiple unrelated responsibilities that can fail and restored
   individually
   

## The registration protocol

As the first step, we need to design the protocol for registering a device and getting an actor that will be responsible
for it. This protocol will be provided by the DeviceManager component itself, because that is the only actor that
is known upfront: groups and device actors are created on-demand. The steps of registering a device are the following:

 1. DeviceManager receives the request to register an actor for a given group and device ID
 2. If the manager already has an actor for the group, it forwards the request to it. Otherwise it first creates
    a new one and then forwards the request.
 3. The DeviceGroup receives the request to register an actor for the given device ID
 4. If the group already has an actor for the device ID, it forwards the request to it. Otherwise it first creates
    a new one and then forwards the request.
 5. The device actor receives the request, and acknowledges it to the original sender. Since he is the sender of
    the acknowledgement, the recevier will be able to learn its `ActorRef` and send direct messages to it in the future.
    
Now that the steps are defined, we only need to define the messages that we will use to communicate requests and
their acknowledgement:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceManager.scala) { #device-manager-msgs }

As you see, in this case we have not included a request ID field in the messages. Since registration is usually happening
once, at the component that connects the system to some network protocol, we will usually have no use for the ID. 
Nevertheless, it is a good exercise to add this ID.

## Add registration support to Device actor

We start implementing the protocol from the bottom first. In practice both a top-down and bottom-up approach can
work, but in our case we benefit from the bottom-up approach as it allows us to immediately write tests for the
new features without mocking out parts.

At the bottom are the Device actors. Their job in this registration process is rather simple, just reply to the
registration request with an acknowledgement to the sender. *We will assume that the sender of the registration
message is preserved in the upper layers.* We will show you in the next section how this can be achieved. 

We also add a safeguard against requests that come with a mismatched group or device ID. This is how the resulting
code looks like:

> NOTE: We used a feature of scala pattern matching where we can match if a certain field equals to an expected
value. This is achieved by variables included in backticks, like `` `variable` ``, and it means that the pattern
only match if it contains the value of `variable` in that position.

@@snip [Hello.scala](../../../test/scala/tutorial_3/Device.scala) { #device-with-register }

We should not leave features untested, so we immediately write two new test cases, one exercising successful 
registration, the other testing the case when IDs don't match:

> NOTE: We used the `expectNoMsg()` helper method from `TestProbe`. This assertion waits until the defined time-limit
and fails if it receives any messages during this period. If no messages are received during the wait period the
assertion passes. It is usually a good idea to keep these timeouts low (but not too low) because they add significant
test execution time otherwise.

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceSpec.scala) { #device-registration-tests }

## Device group

We are done with the registration support at the device level, now we have to implement it at the group level. A group
has more work to do when it comes to registrations. It must either forward the request to an existing child, or it
should create one. To be able to look up child actors by their device IDs we will use a `Map`. 

We also want to keep the original sender of the request so that our device actor can reply directly. This is possible
by using `forward` instead of the `!` operator. The only difference between the two is that `forward` keeps the original
sender while `!` always sets the sender to be the current actor. Just like with our device, we ensure that we don't
respond to wrong group IDs:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroup.scala) { #device-group-register }

Just as we did with the device, we test this new functionality. We also test that the actors returned for the two
different IDs are actually different, and we also attempt to record a temperature reading for each of the devices
to see if the actors are responding.

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroupSpec.scala) { #device-group-test-registration }

It might be, that a device actor already exists for the registration request. In this case we would like to use
the existing actor instead of a new one. We have not tested this yet, so we need to fix this:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroupSpec.scala) { #device-group-test3 }

So far, we have implemented everything for registering device actors in the group. Devices come and go however, so
we will need a way to remove those from the `Map`. We will assume that when a device is removed, its corresponding actor
is simply stopped. We need some way for the parent to be notified when one of the device actors are stopped. Unfortunately
supervision will not help, because it is used for error scenarios not graceful stopping. 

There is a feature in Akka that is exactly what we need here. It is possible for an actor to _watch_ another actor
and be notified if the other actor is stopped. This feature is called _Death Watch_ and it is an important tool for 
any Akka application. Unlike supervision, watching is not limited to parent-child relationships, any actor can watch
any other actor given its `ActorRef`. After a watched actor stops, the watcher receives a `Terminated(ref)` message
which also contains the reference of the watched actor. The watcher can either handle this message explicitly, or, if
it does not handle it directly it will fail with a `DeathPactException`. This latter is useful if the actor cannot
longer perform its duties after its collaborator actor has been stopped. In our case, the group should still function
after one device have been stopped, so we need to handle this message. The steps we need to follow are the following:

 1. Whenever we create a new device actor, we must also watch it
 2. When we are notified that a device actor has been stopped we also need to remove it from the `Map` that maps
    device IDs to children
    
Unfortunately, the `Terminated` message contains only contains the `ActorRef` of the child actor but we does not know
its ID, which we need to remove it from the `Map` of existing ID-actor mappings. To be able to do this removal, we
need to introduce a second `Map` that allow us to find out the device ID corresponding to a given `ActorRef`. Putting
this together the result is:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroup.scala) { #device-group-remove }

Since so far we have no means to get from the group what are the devices it thinks are active, we cannot test our
new functionality yet. To make it testable, we add a new query capability that simply lists the currently active
device IDs:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroup.scala) { #device-group-full }

We have now almost everything to test the removal of devices. We only need now ways to:

 * stop a device actor from our test case, from the outside: any actor can be stopped by simply sending a special
   built-in message, `PoisonPill`, which instructs the actor to stop. 
 * be notified once the device actor is stopped: we can use the _Death Watch_ facility for this purpose, too. Thankfully
   the `TestProbe` has two messages that we can easily use, `watch()` to watch a specific actor, and `expectTerminated`
   to assert that the watched actor has been terminated.
   
We add two more test cases now. In the first, we just test that we get back the list of proper IDs once we have added
a few devices. The second test case makes sure that the device ID is properly removed after the device actor has
 been stopped:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceGroupSpec.scala) { #device-group-list-terminate-test }

## Device manager

The only part that remains now is the entry point for our device manager component. This actor is very similar to
the group actor, with the only difference that it creates group actors instead of device actors:

@@snip [Hello.scala](../../../test/scala/tutorial_3/DeviceManager.scala) { #device-manager-full }

We leave tests of the device manager as an exercise as it is very similar to the tests we have written for the group
actor.

## What is next?

We have now a hierarchic component for registering and tracking devices and recording measurements. We have seen
some conversation patterns like

 * request-respond (for temperature recorings)
 * delegate-respond (for registration of devices)
 * create-watch-terminate (for creating group and device actor as children)
 
In the next chapter we will introduce group query capabilities, which will establish a new conversation pattern of 
scatter-gather. In particular, we will implement the functionality that allows users to query the status of all
the devices belonging to a group.