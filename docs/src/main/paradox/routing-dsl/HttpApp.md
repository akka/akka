# HttpApp Bootstrap

@@@ warning { title="API may change" }
This is experimental and the API is subjected to change in future releases of Akka HTTP.
For further information about this marker, see [The @DoNotInherit and @ApiMayChange markers](https://doc.akka.io/docs/akka/current/common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers)
in the Akka documentation.
@@@

@@toc { depth=1 }

## Introduction

The objective of @unidoc[HttpApp] is to help you start an HTTP server with just a few lines of code.
This is accomplished just by extending @unidoc[HttpApp] and implementing the `routes()` method.
If desired, @unidoc[HttpApp] provides different hook methods that can be overridden to change its default behavior.

Please note that @unidoc[HttpApp] is not the canonical way of starting an akka-http server. It is a tool to get up and running fast. For integrating into already existing Akka applications that already bring their @unidoc[ActorSystem] using `Http.bindAndHandle` (which is also just a single line of code) is recommended over using @unidoc[HttpApp].

## Minimal Example

The following example shows how to start a server:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #minimal-routing-example }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #minimal-imports #minimal-routing-example }

Firstly we define @scala[an `object` (it can also be a `class`)]@java[a `class`] that extends @unidoc[HttpApp] and we just implement the routes this server will handle.
After that, we can start a server just by providing a `host` and a `port`. Calling `startServer` blocks the current thread until the server is signaled for termination.
The default behavior of @unidoc[HttpApp] is to start a server, and shut it down after `ENTER` is pressed. When the call to `startServer` returns the server is properly shut down.

## Reacting to Bind Failures

@unidoc[HttpApp] provides different hooks that will be called after a successful and unsuccessful initialization. For example, the server
might not start due to the port being already in use, or because it is a privileged one.

Here you can see an example server that overrides the `postHttpBindingFailure` hook and prints the error to the console (this is also the default behavior)

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #failed-binding-example }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #imports #bindingError }

So if the port `80` would be already taken by another app, the call to `startServer` returns immediately and the `postHttpBindingFailure` hook will be called.

## Providing your own Server Settings

@unidoc[HttpApp] reads the default @unidoc[ServerSettings] when one is not provided.
In case you want to provide different settings, you can simply pass it to `startServer` as illustrated in the following example:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #with-settings-routing-example }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #imports #with-settings-routing-example }

## Providing your own Actor System

@unidoc[HttpApp] creates its own @unidoc[ActorSystem] instance when one is not provided.
In case you already created an @unidoc[ActorSystem] in your application you can
pass it to `startServer` as illustrated in the following example:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #with-actor-system }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #minimal-imports #ownActorSystem }

When you provide your own @unidoc[ActorSystem] you are responsible for terminating it. For more fine-grained control over the shutdown of various parts of the application, take a look at @scala[@extref[Coordinated Shutdown](akka25-docs:scala/actors.html#coordinated-shutdown)]@java[@extref[Coordinated Shutdown](akka25-docs:java/actors.html#coordinated-shutdown)] extension which is available since Akka 2.5.0.



## Providing your own Actor System and Settings

@unidoc[HttpApp] offers another overloaded `startServer` method where you can pass, on top of the `host` and `port`,
your previously created @unidoc[ActorSystem] and your custom @unidoc[ServerSettings].
You can see an example in the following code snippet:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #with-actor-system-settings }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #imports #ownActorSystemAndSettings }

## Overriding Termination Signal

As already described previously, the default trigger that shuts down the server is pressing `ENTER`.
For simple examples this is sufficient, but for bigger applications this is, most probably, not what you want to do.
@unidoc[HttpApp] can be configured to signal the server termination just by overriding the method `waitForShutdownSignal`.
This method must return a @scala[`Future`]@java[`CompletionStage`] that, when terminated, will shutdown the server.

This following example shows how to override the default termination signal:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #override-termination-signal }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #imports #selfClosing #serverTerminationSignal }

Here the termination signal is defined by a future that will be automatically completed after 5 seconds. 

## Getting Notified on Server Shutdown

There are some cases in which you might want to clean up any resources you were using in your server. In order to do this
in a coordinated way, you can override @unidoc[HttpApp]'s `postServerShutdown` method.

Here you can find an example:

Scala
:   @@snip [HttpAppExampleSpec.scala]($test$/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #cleanup-after-shutdown }

Java
:   @@snip [HttpAppExampleTest.java]($test$/java/docs/http/javadsl/server/HttpAppExampleTest.java) { #imports #postShutdown }

The `postServerShutdown` method will be only called once the server attempt to shutdown has completed. Please notice that in
the @scala[case that `unbind` fails to stop the server, this method will also be called with a failed `Try`.]@java[exception that this method is called with, may be null. It will be a non-null one only when `unbind` fails to stop the server.]
