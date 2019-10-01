# Classic Persistent FSM

@@include[includes.md](includes.md) { #actor-api }

## Dependency

Persistent FSMs are part of Akka persistence, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-persistence_$scala.binary_version$"
  version="$akka.version$"
}

@@@ warning

Persistent FSM is no longer actively developed and will be replaced by @ref[Akka Persistence Typed](typed/persistence.md). It is not advised
to build new applications with Persistent FSM. Existing users of Persistent FSM @ref[should migrate](#migration-to-eventsourcedbehavior). 

@@@

@scala[`PersistentFSM`]@java[`AbstractPersistentFSM`] handles the incoming messages in an FSM like fashion.
Its internal state is persisted as a sequence of changes, later referred to as domain events.
Relationship between incoming messages, FSM's states and transitions, persistence of domain events is defined by a DSL.

### A Simple Example

To demonstrate the features of the @scala[`PersistentFSM` trait]@java[`AbstractPersistentFSM`], consider an actor which represents a Web store customer.
The contract of our "WebStoreCustomerFSMActor" is that it accepts the following commands:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-commands }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-commands }

`AddItem` sent when the customer adds an item to a shopping cart
`Buy` - when the customer finishes the purchase
`Leave` - when the customer leaves the store without purchasing anything
`GetCurrentCart` allows to query the current state of customer's shopping cart

The customer can be in one of the following states:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-states }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-states }

`LookingAround` customer is browsing the site, but hasn't added anything to the shopping cart
`Shopping` customer has recently added items to the shopping cart
`Inactive` customer has items in the shopping cart, but hasn't added anything recently
`Paid` customer has purchased the items

@@@ note

@scala[`PersistentFSM`]@java[`AbstractPersistentFSM`] states must @scala[inherit from trait]@java[implement interface] `PersistentFSM.FSMState` and implement the
@scala[`def identifier: String`]@java[`String identifier()`] method. This is required in order to simplify the serialization of FSM states.
String identifiers should be unique!

@@@

Customer's actions are "recorded" as a sequence of "domain events" which are persisted. Those events are replayed on an actor's
start in order to restore the latest customer's state:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-domain-events }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-domain-events }

Customer state data represents the items in a customer's shopping cart:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-states-data }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-states-data }

Here is how everything is wired together:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-fsm-body }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-fsm-body }

@@@ note

State data can only be modified directly on initialization. Later it's modified only as a result of applying domain events.
Override the `applyEvent` method to define how state data is affected by domain events, see the example below

@@@

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-apply-event }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-apply-event }

`andThen` can be used to define actions which will be executed following event's persistence - convenient for "side effects" like sending a message or logging.
Notice that actions defined in `andThen` block are not executed on recovery:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-andthen-example }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-andthen-example }

A snapshot of state data can be persisted by calling the `saveStateSnapshot()` method:

Scala
:  @@snip [PersistentFSMSpec.scala](/akka-persistence/src/test/scala/akka/persistence/fsm/PersistentFSMSpec.scala) { #customer-snapshot-example }

Java
:  @@snip [AbstractPersistentFSMTest.java](/akka-persistence/src/test/java/akka/persistence/fsm/AbstractPersistentFSMTest.java) { #customer-snapshot-example }

On recovery state data is initialized according to the latest available snapshot, then the remaining domain events are replayed, triggering the
`applyEvent` method.


## Migration to EventSourcedBehavior

Persistent FSMs can be represented using @ref[Persistence Typed](typed/persistence.md). The data stored by Persistence FSM can be read by an @apidoc[EventSourcedBehavior]
using a snapshot adapter and an event adapter. The adapters are required as Persistent FSM doesn't store snapshots and user data directly, it wraps them in 
internal types that include state transition information.

Before reading the migration guide it is advised to understand @ref:[Persistence Typed](typed/persistence.md). 

### Migration steps

1. Modify or create new commands to include `replyTo` @apidoc[akka.actor.typed.ActorRef]
1. Typically persisted events will remain the same
1. Create an `EventSourcedBehavior` that mimics the old `PersistentFSM`
1. Replace any state timeouts with `Behaviors.withTimers` either hard coded or stored in the state
1. Add an @apidoc[akka.persistence.typed.EventAdapter] to convert state transition events added by `PersistentFSM` into private events or filter them
1. If snapshots are used add a @apidoc[akka.persistence.typed.SnapshotAdapter] to convert PersistentFSM snapshots into the `EventSourcedBehavior`s `State`

The following is the shopping cart example above converted to an `EventSourcedBehavior`. 

The new commands, note the replyTo field for getting the current cart.

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #commands }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #commands }

The states of the FSM are represented using the `EventSourcedBehavior`'s state parameter along with the event and command handlers. Here are the states:

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #state }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #state }

The command handler has a separate section for each of the PersistentFSM's states: 

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #command-handler }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #command-handler }

Note that there is no explicit support for state timeout as with PersistentFSM but the same behavior can be achieved
using `Behaviors.withTimers`. If the timer is the same for all events then it can be hard coded, otherwise the
old PersistentFSM timeout can be taken from the `StateChangeEvent` in the event adapter and is also available when
constructing a `SnapshotAdapter`. This can be added to an internal event and then stored in the `State`. Care
must also be taken to restart timers on recovery in the signal handler:

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #signal-handler }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #signal-handler }

Then the event handler:

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #event-handler }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #event-handler }

The last step is the adapters that will allow the new @apidoc[EventSourcedBehavior] to read the old data:

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #event-adapter }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #event-adapter }

The snapshot adapter needs to adapt an internal type of PersistentFSM so a helper function is provided to build the @apidoc[SnapshotAdapter]:

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #snapshot-adapter }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #snapshot-adapter }

That concludes all the steps to allow an @apidoc[EventSourcedBehavior] to read a `PersistentFSM`'s data. Once the new code has been running
you can not roll back as the PersistentFSM will not be able to read data written by Persistence Typed. 

@@@ note 

There is one case where @ref:[a full shutdown and startup is required](additional/rolling-updates.md#migrating-from-persistentfsm-to-eventsourcedbehavior).

@@@

