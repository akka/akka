# Extending Akka

Akka extensions can be used for almost anything, they provide a way to create
an instance of a class only once for the whole ActorSystem and be able to access
it from anywhere. Akka features such as Cluster, Serialization and Sharding are all
Akka extensions. Below is the use-case of managing an expensive database connection 
pool and accessing it from various places in your application.

You can choose to have your Extension loaded on-demand or at `ActorSystem` creation 
time through the Akka configuration.
Details on how to make that happens are below, in the @ref:[Loading from Configuration](extending.md#loading) section.

@@@ warning

Since an extension is a way to hook into Akka itself, the implementor of the extension needs to
ensure the thread safety and that it is non-blocking.

@@@

## Building an extension

Let's build an extension to manage a shared database connection pool.

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/extensions/ExtensionDocSpec.scala) { #shared-resource }

Java
:  @@snip [ExtensionDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/extensions/ExtensionDocTest.java) { #shared-resource }

First create an @apidoc[akka.actor.typed.Extension], this will be created only once per ActorSystem:

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/extensions/ExtensionDocSpec.scala) { #extension }

Java
:  @@snip [ExtensionDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/extensions/ExtensionDocTest.java) { #extension }

This is the public API of your extension. Internally in this example we instantiate our expensive database connection.
The `DatabaseConnectionPool` can be looked up this way any number of times and it will return the same instance. 

Then create an @apidoc[akka.actor.typed.ExtensionId] to identify the extension.

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/extensions/ExtensionDocSpec.scala) { #extension-id }

Java
:  @@snip [ExtensionDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/extensions/ExtensionDocTest.java) { #extension-id }

Then finally to use the extension it can be looked up:

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/extensions/ExtensionDocSpec.scala) { #usage }

Java
:  @@snip [ExtensionDocTest.java](/akka-actor-typed-tests/src/test/java/jdocs/akka/typed/extensions/ExtensionDocTest.java) { #usage  }

<a id="loading"></a>
## Loading from configuration

To be able to load extensions from your Akka configuration you must add FQCNs of implementations of the `ExtensionId`
in the `akka.actor.typed.extensions` section of the config you provide to your `ActorSystem`.

Scala
:  @@snip [ExtensionDocSpec.scala](/akka-actor-typed-tests/src/test/scala/docs/akka/typed/extensions/ExtensionDocSpec.scala) { #config }

Java
:   ```ruby
   akka.actor.typed {
     extensions = ["jdocs.akka.extensions.ExtensionDocTest$DatabaseConnectionPoolId"]
   }
   ```
     










