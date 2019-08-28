# Mailboxes

## Dependency

Mailboxes are part of core Akka, which means that they are part of the akka-actor-typed dependency:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-actor-typed_$scala.binary_version$"
  version="$akka.version$"
}

## Introduction 

Each actor in Akka has a `Mailbox`, this is where the messages are enqueued before being processed by the actor.

By default an unbounded mailbox is used, this means any number of messages can be enqueued into the mailbox. 

The unbounded mailbox is a convenient default but in a scenario where messages are added to the mailbox faster
than the actor can process them, this can lead to the application running out of memory.
For this reason a bounded mailbox can be specified, the bounded mailbox will pass new messages to `deadletters` when the
mailbox is full.

For advanced use cases it is also possible to defer mailbox selection to config by pointing to a config path.

## Selecting what mailbox is used

### Selecting a Mailbox Type for an Actor

To select a specific mailbox for an actor use `MailboxSelector` to create a `Props` instance for spawning your actor:

Scala
:  @@snip [MailboxDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/MailboxDocSpec.scala) { #select-mailbox }

Java
:  @@snip [MailboxDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/MailboxDocTest.java) { #select-mailbox }

`fromConfig` takes an absolute config path to a block defining the dispatcher in the config file:

@@snip [MailboxDocSpec.scala](/akka-actor-typed-tests/src/test/resources/mailbox-config-sample.conf) { }

### Requiring a Message Queue Type for a Dispatcher

A dispatcher may also have a requirement for the mailbox type used by the
actors running on it. An example is the @apidoc[BalancingDispatcher] which requires a
message queue that is thread-safe for multiple concurrent consumers. Such a
requirement is formulated within the dispatcher configuration section:

```
my-dispatcher {
  mailbox-requirement = org.example.MyInterface
}
```

The given requirement names a class or interface which will then be ensured to
be a supertype of the message queue's implementation. In case of a
conflict—e.g. if the actor requires a mailbox type which does not satisfy this
requirement—then actor creation will fail.

### How the Mailbox Type is Selected

When an actor is created, the `ActorRefProvider` first determines the
dispatcher which will execute it. Then the mailbox is determined as follows:

 1. If the actor's deployment configuration section contains a `mailbox` key,
this refers to a configuration section describing the mailbox type.
 2. If the actor's `Props` contains a mailbox selection then that names a configuration section describing the
mailbox type to be used. This needs to be an absolute config path,
for example `myapp.special-mailbox`, and is not nested inside the `akka` namespace.
 3. If the dispatcher's configuration section contains a `mailbox-type` key
the same section will be used to configure the mailbox type.
 4. If the actor requires a mailbox type as described above then the mapping for
that requirement will be used to determine the mailbox type to be used; if
that fails then the dispatcher's requirement—if any—will be tried instead.
 5. If the dispatcher requires a mailbox type as described above then the
mapping for that requirement will be used to determine the mailbox type to
be used.
 6. The default mailbox `akka.actor.default-mailbox` will be used.

### Default Mailbox

The default mailbox is used when the mailbox is not specified.
This is an unbounded mailbox, backed by a
`java.util.concurrent.ConcurrentLinkedQueue`.

`SingleConsumerOnlyUnboundedMailbox` is an even more efficient mailbox, and
it can be used as the default mailbox, but it cannot be used with a BalancingDispatcher.

Configuration of `SingleConsumerOnlyUnboundedMailbox` as default mailbox:

```
akka.actor.default-mailbox {
  mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"
}
```

### Which Configuration is passed to the Mailbox Type

Each mailbox type is implemented by a class which extends `MailboxType`
and takes two constructor arguments: a `ActorSystem.Settings` object and
a `Config` section. The latter is computed by obtaining the named
configuration section from the `ActorSystem` configuration, overriding its
`id` key with the configuration path of the mailbox type and adding a
fall-back to the default mailbox configuration section.

## Mailbox Implementations

Akka ships with a number of mailbox implementations:

 * 
   **UnboundedMailbox** (default)
    * The default mailbox
    * Backed by a `java.util.concurrent.ConcurrentLinkedQueue`
    * Blocking: No
    * Bounded: No
    * Configuration name: `"unbounded"` or `"akka.dispatch.UnboundedMailbox"`
 * 
   **SingleConsumerOnlyUnboundedMailbox**
   Depending on your use case, this queue may or may not be faster than the default one — be sure to benchmark properly!
    * Backed by a Multiple-Producer Single-Consumer queue, cannot be used with `BalancingDispatcher`
    * Blocking: No
    * Bounded: No
    * Configuration name: `"akka.dispatch.SingleConsumerOnlyUnboundedMailbox"`
 * 
   **NonBlockingBoundedMailbox**
    * Backed by a very efficient Multiple-Producer Single-Consumer queue
    * Blocking: No (discards overflowing messages into deadLetters)
    * Bounded: Yes
    * Configuration name: `"akka.dispatch.NonBlockingBoundedMailbox"`
 * 
   **UnboundedControlAwareMailbox**
    * Delivers messages that extend `akka.dispatch.ControlMessage` with higher priority
    * Backed by two `java.util.concurrent.ConcurrentLinkedQueue`
    * Blocking: No
    * Bounded: No
    * Configuration name: "akka.dispatch.UnboundedControlAwareMailbox"
 * 
   **UnboundedPriorityMailbox**
    * Backed by a `java.util.concurrent.PriorityBlockingQueue`
    * Delivery order for messages of equal priority is undefined - contrast with the UnboundedStablePriorityMailbox
    * Blocking: No
    * Bounded: No
    * Configuration name: "akka.dispatch.UnboundedPriorityMailbox"
 * 
   **UnboundedStablePriorityMailbox**
    * Backed by a `java.util.concurrent.PriorityBlockingQueue` wrapped in an `akka.util.PriorityQueueStabilizer`
    * FIFO order is preserved for messages of equal priority - contrast with the UnboundedPriorityMailbox
    * Blocking: No
    * Bounded: No
    * Configuration name: "akka.dispatch.UnboundedStablePriorityMailbox"

Other bounded mailbox implementations which will block the sender if the capacity is reached and
configured with non-zero `mailbox-push-timeout-time`. 

@@@ note

The following mailboxes should only be used with zero `mailbox-push-timeout-time`.

@@@

 * **BoundedMailbox**
    * Backed by a `java.util.concurrent.LinkedBlockingQueue`
    * Blocking: Yes if used with non-zero `mailbox-push-timeout-time`, otherwise No
    * Bounded: Yes
    * Configuration name: "bounded" or "akka.dispatch.BoundedMailbox"
 * **BoundedPriorityMailbox**
    * Backed by a `java.util.PriorityQueue` wrapped in an `akka.util.BoundedBlockingQueue`
    * Delivery order for messages of equal priority is undefined - contrast with the `BoundedStablePriorityMailbox`
    * Blocking: Yes if used with non-zero `mailbox-push-timeout-time`, otherwise No
    * Bounded: Yes
    * Configuration name: `"akka.dispatch.BoundedPriorityMailbox"`
 * **BoundedStablePriorityMailbox**
    * Backed by a `java.util.PriorityQueue` wrapped in an `akka.util.PriorityQueueStabilizer` and an `akka.util.BoundedBlockingQueue`
    * FIFO order is preserved for messages of equal priority - contrast with the BoundedPriorityMailbox
    * Blocking: Yes if used with non-zero `mailbox-push-timeout-time`, otherwise No
    * Bounded: Yes
    * Configuration name: "akka.dispatch.BoundedStablePriorityMailbox"
 * **BoundedControlAwareMailbox**
    * Delivers messages that extend `akka.dispatch.ControlMessage` with higher priority
    * Backed by two `java.util.concurrent.ConcurrentLinkedQueue` and blocking on enqueue if capacity has been reached
    * Blocking: Yes if used with non-zero `mailbox-push-timeout-time`, otherwise No
    * Bounded: Yes
    * Configuration name: "akka.dispatch.BoundedControlAwareMailbox"

## Custom Mailbox type

The best way to show how to create your own Mailbox type is by example:

Scala
:   @@snip [MyUnboundedMailbox.scala](/akka-docs/src/test/scala/docs/dispatcher/MyUnboundedMailbox.scala) { #mailbox-marker-interface }

Java
:   @@snip [MyUnboundedMessageQueueSemantics.java](/akka-docs/src/test/java/jdocs/dispatcher/MyUnboundedMessageQueueSemantics.java) { #mailbox-marker-interface }


Scala
:   @@snip [MyUnboundedMailbox.scala](/akka-docs/src/test/scala/docs/dispatcher/MyUnboundedMailbox.scala) { #mailbox-implementation-example }

Java
:   @@snip [MyUnboundedMailbox.java](/akka-docs/src/test/java/jdocs/dispatcher/MyUnboundedMailbox.java) { #mailbox-implementation-example }

And then you specify the FQCN of your MailboxType as the value of the "mailbox-type" in the dispatcher
configuration, or the mailbox configuration.

@@@ note

Make sure to include a constructor which takes
`akka.actor.ActorSystem.Settings` and `com.typesafe.config.Config`
arguments, as this constructor is invoked reflectively to construct your
mailbox type. The config passed in as second argument is that section from
the configuration which describes the dispatcher or mailbox setting using
this mailbox type; the mailbox type will be instantiated once for each
dispatcher or mailbox setting using it.

@@@

