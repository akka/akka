<a id="http-app"></a>
# HttpApp Bootstrap

@@@ warning { title="API may change" }
This is experimental and the API is subjected to change in future releases of Akka HTTP.
For further information about this marker, see [The @DoNotInherit and @ApiMayChange markers](http://doc.akka.io/docs/akka/current/common/binary-compatibility-rules.html#The_@DoNotInherit_and_@ApiMayChange_markers)
in the Akka documentation.
@@@

@@toc { depth=1 }

## Introduction

The objective of `HttpApp` is to help you start an HTTP server with just a few lines of code.
This is accomplished just by extending `HttpApp` and implementing the `route()` method.
If desired, `HttpApp` provides different hook methods that can be overridden to change its default behavior.

## Minimal Example

The following example shows how to start a server:

@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #minimal-routing-example }

Firstly we define an `object` (it can also be a `class`) that extends `HttpApp` and we just implement the routes this server will handle.
After that, we can start a server just by providing a `host` and a `port`. Calling `startServer` blocks the current thread until the server is signaled for termination.
The default behavior of `HttpApp` is to start a server, and shut it down after `ENTER` is pressed. When the call to `startServer` returns the server is properly shut down.

## Reacting to Bind Failures

`HttpApp` provides different hooks that will be called after a successful and unsuccessful initialization. For example, the server
might not start due to the port being already in use, or because it is a privileged one.

Here you can see an example server that overrides the `postHttpBindingFailure` hook and prints the error to the console (this is also the default behavior)

@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #failed-binding-example }

So if the port `80` would be already taken by another app, the call to `startServer` returns immediately and the `postHttpBindingFailure` hook will be called.

## Providing your own Server Settings

`HttpApp` reads the default `ServerSettings` when one is not provided.
In case you want to provide different settings, you can simply pass it to `startServer` as illustrated in the following example:

@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #with-settings-routing-example }


## Providing your own Actor System

`HttpApp` creates its own `ActorSystem` instance when one is not provided.
In case you already created an `ActorSystem` in your application you can
pass it to `startServer` as illustrated in the following example:
 
@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #with-actor-system }

## Overriding Termination Signal

As already described previously, the default trigger that shuts down the server is pressing `ENTER`.
For simple examples this is sufficient, but for bigger applications this is, most probably, not what you want to do.
`HttpApp` can be configured to signal the server termination just by overriding the method `waitForShutdownSignal`.
This method must return a `Future` that, when terminated, will shutdown the server.

This following example shows how to override the default termination signal:

@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #override-termination-signal }

Here the termination signal is defined by a future that will be automatically completed after 5 seconds. 

## Getting Notified on Server Shutdown

There are some cases in which you might want to clean up any resources you were using in your server. In order to do this
in a coordinated way, you can override `HttpApp`'s `postServerShutdown` method.

Here you can find an example:

@@snip [HttpAppExampleSpec.scala](../../../../../test/scala/docs/http/scaladsl/HttpAppExampleSpec.scala) { #cleanup-after-shutdown }

The `postServerShutdown` method will be only called once the server attempt to shutdown has completed. Please notice that in
the case that `unbind` fails to stop the server, this method will also be called with a failed `Try`.
