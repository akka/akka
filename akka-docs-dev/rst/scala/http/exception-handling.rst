.. _Exception Handling:

Exception Handling
==================

Exceptions thrown during route execution bubble up through the route structure to the next enclosing
:ref:`-handleExceptions-` directive, ``Route.seal`` or the ``onFailure`` callback of a
future created by ``detach``.

Similarly to the way that :ref:`Rejections` are handled the :ref:`-handleExceptions-` directive delegates the actual job of
converting a list of rejections to its argument, an ExceptionHandler__, which is defined like this::

    trait ExceptionHandler extends PartialFunction[Throwable, Route]

__ @github@/akka-http/src/main/scala/akka/http/server/ExceptionHandler.scala

:ref:`runRoute` defined in :ref:`HttpService` does the same but gets its ``ExceptionHandler`` instance
implicitly.

Since an ``ExceptionHandler`` is a partial function it can choose, which exceptions it would like to handle and
which not. Unhandled exceptions will simply continue to bubble up in the route structure. The top-most
``ExceptionHandler`` applied by :ref:`runRoute` will handle *all* exceptions that reach it.

So, if you'd like to customize the way certain exceptions are handled simply bring a custom ``ExceptionHandler`` into
implicit scope of :ref:`runRoute` or pass it to an explicit :ref:`-handleExceptions-` directive that you
have put somewhere into your route structure.

Here is an example:

.. includecode2:: ../code/docs/http/server/ExceptionHandlerExamplesSpec.scala
   :snippet: example-1
