# Building a storage backend for Durable State 

Storage backends for state stores are pluggable in the Akka persistence extension.
This documentation described how to build a new storage backend for durable state.

Applications can provide their own plugins by implementing a plugin API and activating them by configuration.
Plugin development requires the following imports:

Scala
:  @@snip [PersistenceStatePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/state/PersistenceStatePluginDocSpec.scala) { #plugin-imports }

Java
:  @@snip [MyJavaStateStore.java](/akka-docs/src/main/java/docs/persistence/state/MyJavaStateStore.java) { #plugin-imports }

## State Store plugin API

A durable state store plugin extends `DurableStateUpdateStore`. 

`DurableStateUpdateStore` is an interface and the methods to be implemented are:

Scala
:  @@snip [MyStateStore.scala](/akka-docs/src/main/scala/docs/persistence/state/MyStateStore.scala) { #plugin-api }

Java
:  @@snip [MyJavaStateStore.java](/akka-docs/src/main/java/docs/persistence/state/MyJavaStateStore.java) { #state-store-plugin-api }

## State Store provider

A `DurableStateStoreProvider` needs to be implemented to be able to create the plugin itself:

Scala
:  @@snip [MyStateStore.scala](/akka-docs/src/main/scala/docs/persistence/state/MyStateStore.scala) { #plugin-provider }

Java
:  @@snip [MyJavaStateStoreProvider.java](/akka-docs/src/main/java/docs/persistence/state/MyJavaStateStoreProvider.java) { #plugin-provider }

## Configure the State Store

A durable state store plugin can be activated with the following minimal configuration:

Scala
:  @@snip [PersistenceStatePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/state/PersistenceStatePluginDocSpec.scala) { #plugin-config-scala }

Java
:  @@snip [PersistenceStatePluginDocSpec.scala](/akka-docs/src/test/scala/docs/persistence/state/PersistenceStatePluginDocSpec.scala) { #plugin-config-java }

