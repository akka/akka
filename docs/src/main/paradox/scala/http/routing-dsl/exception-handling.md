<a id="exception-handling-scala"></a>
# Exception Handling

Exceptions thrown during route execution bubble up through the route structure to the next enclosing
@ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions) directive or the top of your route structure.

Similarly to the way that @ref[Rejections](rejections.md#rejections-scala) are handled the @ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions) directive delegates the actual job
of converting an exception to its argument, an @github[ExceptionHandler](/akka-http/src/main/scala/akka/http/scaladsl/server/ExceptionHandler.scala), which is defined like this:

```scala
trait ExceptionHandler extends PartialFunction[Throwable, Route]
```

Since an `ExceptionHandler` is a partial function it can choose, which exceptions it would like to handle and
which not. Unhandled exceptions will simply continue to bubble up in the route structure.
At the root of the route tree any still unhandled exception will be dealt with by the top-level handler which always
handles *all* exceptions.

`Route.seal` internally wraps its argument route with the @ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions) directive in order to "catch" and
handle any exception.

So, if you'd like to customize the way certain exceptions are handled you need to write a custom `ExceptionHandler`.
Once you have defined your custom `ExceptionHandler` you have two options for "activating" it:

 1. Bring it into implicit scope at the top-level.
 2. Supply it as argument to the @ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions) directive.

In the first case your handler will be "sealed" (which means that it will receive the default handler as a fallback for
all cases your handler doesn't handle itself) and used for all exceptions that are not handled within the route
structure itself.

The second case allows you to restrict the applicability of your handler to certain branches of your route structure.

Here is an example for wiring up a custom handler via @ref[handleExceptions](directives/execution-directives/handleExceptions.md#handleexceptions):

@@snip [ExceptionHandlerExamplesSpec.scala](../../../../../test/scala/docs/http/scaladsl/server/ExceptionHandlerExamplesSpec.scala) { #explicit-handler-example }

And this is how to do it implicitly:

@@snip [ExceptionHandlerExamplesSpec.scala](../../../../../test/scala/docs/http/scaladsl/server/ExceptionHandlerExamplesSpec.scala) { #implicit-handler-example }

## Default Exception Handler

A default `ExceptionHandler` is used if no custom instance is provided.

It will handle every `NonFatal` throwable, write its stack trace and complete the request
with `InternalServerError` `(500)` status code.

The message body will contain a string obtained via `Throwable#getMessage` call on the exception caught.

In case `getMessage` returns `null` (which is true for e.g. `NullPointerException` instances),
the class name and a remark about the message being null are included in the response body.

Note that `IllegalRequestException`s' stack traces are not logged, since instances of this class
normally contain enough information to provide a useful error message.

@@@ note
Users are strongly encouraged not to rely on the `ExceptionHandler` as a means
of handling 'normally exceptional' situations.

Exceptions are known to have a negative performance impact for cases
when the depth of the call stack is significant (stack trace construction cost)
and when the handler is located far from the place of the throwable instantiation (stack unwinding costs).

In a typical Akka application both these conditions are frequently true,
so as a rule of thumb, you should try to minimize the number of `Throwable` instances
reaching the exception handler.

To understand the performance implications of (mis-)using exceptions,
have a read at this excellent post by A. Shipilёv: [The Exceptional Performance of Lil' Exception](https://shipilev.net/blog/2014/exceptional-performance).
@@@
