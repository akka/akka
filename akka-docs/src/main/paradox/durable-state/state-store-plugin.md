# Building a storage backend for Durable State 

Storage backends for state stores are pluggable in the Akka persistence extension.
This documentation described how to build a new storage backend for durable state.

Applications can provide their own plugins by implementing a plugin API and activating them by configuration.
Plugin development requires the following imports:

Scala
:  @@snip [PersistenceStatePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/state/PersistenceStatePluginDocSpec.scala) { #plugin-imports }

Java
:  @@snip [DurableStateExample.java](/akka-docs/src/test/java/jdocs/persistence/state/DurableStateExample.java) { #plugin-imports }

## State Store plugin API

A durable state store plugin extends `DurableStateUpdateStore`. 

`DurableStateUpdateStore` is an interface and the methods to be implemented are:

Scala
:  @@snip [DurableStateUpdateStore.scala](/akka-persistence/src/main/scala/akka/persistence/state/scaladsl/DurableStateUpdateStore.scala) { #plugin-api }

Java
:  @@snip [DurableStateExample.java](/akka-docs/src/test/java/jdocs/persistence/state/DurableStateExample.java) { #state-store-plugin-api }

A durable state store plugin can be activated with the following minimal configuration:

@@snip [PersistenceStatePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/state/PersistenceStatePluginDocSpec.scala) { #journal-plugin-config }
