# EventSourced behaviors as finite state machines

An @apidoc[EventSourcedBehavior] can be used to represent a persistent FSM. If you're migrating an existing classic
persistent FSM to EventSourcedBehavior see the @ref[migration guide](../persistence-fsm.md#migration-to-eventsourcedbehavior).

To demonstrate this consider an example of a shopping application. A customer can be in the following states:

* Looking around
* Shopping (has something in their basket)
* Inactive
* Paid

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #state }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #state }


And the commands that can result in state changes:

* Add item
* Buy
* Leave 
* Timeout (internal command to discard abandoned purchases)

And the following read only commands:

* Get current cart 

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #commands }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #commands }

The command handler of the EventSourcedBehavior is used to convert the commands that change the state of the FSM
to events, and reply to commands.

@scala[The command handler:]@java[The `forStateType` command handler can be used:]

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #command-handler }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #command-handler }

The event handler is used to change state once the events have been persisted. When the EventSourcedBehavior is restarted
the events are replayed to get back into the correct state.

Scala
:  @@snip [PersistentFsmToTypedMigrationSpec.scala](/akka-persistence-typed/src/test/scala/docs/akka/persistence/typed/PersistentFsmToTypedMigrationSpec.scala) { #event-handler }

Java
:  @@snip [PersistentFsmToTypedMigrationCompileOnlyTest.java](/akka-persistence-typed/src/test/java/jdocs/akka/persistence/typed/PersistentFsmToTypedMigrationCompileOnlyTest.java) { #event-handler }


