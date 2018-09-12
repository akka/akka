# Persistent FSM

## Dependency

Persistent FSMs are part of Akka persistence, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-persistence_$scala.binary_version$"
  version="$akka.version$"
}

<a id="persistent-fsm"></a>
## Persistent FSM

@@@ warning

Persistent FSM is no longer actively developed and will be replaced by @ref[Akka Typed Persistence](typed/persistence.md). It is not advised
to build new applications with Persistent FSM.

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


