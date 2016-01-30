.. _exception-handling-scala:

Exception Handling
==================

Exceptions thrown during route execution bubble up through the route structure to the next enclosing
:ref:`-handleExceptions-` directive or the top of your route structure.

Similarly to the way that :ref:`rejections-scala` are handled the :ref:`-handleExceptions-` directive delegates the actual job
of converting an exception to its argument, an ExceptionHandler__, which is defined like this::

    trait ExceptionHandler extends PartialFunction[Throwable, Route]

__ @github@/akka-http/src/main/scala/akka/http/scaladsl/server/ExceptionHandler.scala

Since an ``ExceptionHandler`` is a partial function it can choose, which exceptions it would like to handle and
which not. Unhandled exceptions will simply continue to bubble up in the route structure.
At the root of the route tree any still unhandled exception will be dealt with by the top-level handler which always
handles *all* exceptions.

``Route.seal`` internally wraps its argument route with the :ref:`-handleExceptions-` directive in order to "catch" and
handle any exception.

So, if you'd like to customize the way certain exceptions are handled you need to write a custom ``ExceptionHandler``.
Once you have defined your custom ``ExceptionHandler`` you have two options for "activating" it:

1. Bring it into implicit scope at the top-level.
2. Supply it as argument to the :ref:`-handleExceptions-` directive.

In the first case your handler will be "sealed" (which means that it will receive the default handler as a fallback for
all cases your handler doesn't handle itself) and used for all exceptions that are not handled within the route
structure itself.

The second case allows you to restrict the applicability of your handler to certain branches of your route structure.

Here is an example for wiring up a custom handler via :ref:`-handleExceptions-`:

.. includecode2:: ../../code/docs/http/scaladsl/server/ExceptionHandlerExamplesSpec.scala
   :snippet: explicit-handler-example


And this is how to do it implicitly:

.. includecode2:: ../../code/docs/http/scaladsl/server/ExceptionHandlerExamplesSpec.scala
   :snippet: implicit-handler-example