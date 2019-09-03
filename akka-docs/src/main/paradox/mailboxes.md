# Classic Mailboxes

@@include[includes.md](includes.md) { #actor-api }
For the new API see @ref:[mailboxes](typed/mailboxes.md).

## Dependency

To use Mailboxes, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction

An Akka `Mailbox` holds the messages that are destined for an `Actor`.
Normally each `Actor` has its own mailbox, but with for example a `BalancingPool`
all routees will share a single mailbox instance.

For more details on advanced mailbox config and custom mailbox implementations, see @ref[Mailboxes](typed/mailboxes.md#mailbox-implementations).

## Mailbox Selection

### Requiring a Message Queue Type for an Actor

It is possible to require a certain type of message queue for a certain type of actor
by having that actor @scala[extend]@java[implement] the parameterized @scala[trait]@java[interface] `RequiresMessageQueue`. Here is
an example:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #required-mailbox-class }

Java
:   @@snip [MyBoundedActor.java](/akka-docs/src/test/java/jdocs/actor/MyBoundedActor.java) { #my-bounded-classic-actor }

The type parameter to the `RequiresMessageQueue` @scala[trait]@java[interface] needs to be mapped to a mailbox in
configuration like this:

@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #bounded-mailbox-config #required-mailbox-config }

Now every time you create an actor of type `MyBoundedActor` it will try to get a bounded
mailbox. If the actor has a different mailbox configured in deployment, either directly or via
a dispatcher with a specified mailbox type, then that will override this mapping.

@@@ note

The type of the queue in the mailbox created for an actor will be checked against the required type in the
@scala[trait]@java[interface] and if the queue doesn't implement the required type then actor creation will fail.

@@@

### Requiring a Message Queue Type for a Dispatcher

A dispatcher may also have a requirement for the mailbox type used by the
actors running on it. This can be configured as documented at [typed/mailboxes.md#requiring-a-message-queue-type-for-a-dispatcher]

## Mailbox configuration examples

### PriorityMailbox

How to create a PriorityMailbox:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #prio-mailbox }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #prio-mailbox }

And then add it to the configuration:

@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #prio-dispatcher-config }

And then an example on how you would use it:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #prio-dispatcher }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #prio-dispatcher }

It is also possible to configure a mailbox type directly like this (this is a top-level configuration entry):

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #prio-mailbox-config #mailbox-deployment-config }

Java
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #prio-mailbox-config-java #mailbox-deployment-config }

And then use it either from deployment like this:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-mailbox-in-config }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-mailbox-in-config }

Or code like this:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #defining-mailbox-in-code }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #defining-mailbox-in-code }

### ControlAwareMailbox

A `ControlAwareMailbox` can be very useful if an actor needs to be able to receive control messages
immediately no matter how many other messages are already in its mailbox.

It can be configured like this:

@@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #control-aware-mailbox-config }

Control messages need to extend the `ControlMessage` trait:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #control-aware-mailbox-messages }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #control-aware-mailbox-messages }

And then an example on how you would use it:

Scala
:   @@snip [DispatcherDocSpec.scala](/akka-docs/src/test/scala/docs/dispatcher/DispatcherDocSpec.scala) { #control-aware-dispatcher }

Java
:   @@snip [DispatcherDocTest.java](/akka-docs/src/test/java/jdocs/dispatcher/DispatcherDocTest.java) { #control-aware-dispatcher }

## Special Semantics of `system.actorOf`

In order to make `system.actorOf` both synchronous and non-blocking while
keeping the return type `ActorRef` (and the semantics that the returned
ref is fully functional), special handling takes place for this case. Behind
the scenes, a hollow kind of actor reference is constructed, which is sent to
the system’s guardian actor who actually creates the actor and its context and
puts those inside the reference. Until that has happened, messages sent to the
`ActorRef` will be queued locally, and only upon swapping the real
filling in will they be transferred into the real mailbox. Thus,

Scala
:   @@@vars
    ```scala
    val props: Props = ...
    // this actor uses MyCustomMailbox, which is assumed to be a singleton
    system.actorOf(props.withDispatcher("myCustomMailbox")) ! "bang"
    assert(MyCustomMailbox.instance.getLastEnqueuedMessage == "bang")
    ```
    @@@

Java
:   @@@vars
    ```java
    final Props props = ...
    // this actor uses MyCustomMailbox, which is assumed to be a singleton
    system.actorOf(props.withDispatcher("myCustomMailbox").tell("bang", sender);
    assert(MyCustomMailbox.getInstance().getLastEnqueued().equals("bang"));
    ```
    @@@

will probably fail; you will have to allow for some time to pass and retry the
check à la `TestKit.awaitCond`.
