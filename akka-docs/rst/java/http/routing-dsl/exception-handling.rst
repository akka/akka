.. _exception-handling-java:

Exception Handling
==================

Exceptions thrown during route execution bubble up through the route structure to the next enclosing
:ref:`-handleExceptions-java-` directive or the top of your route structure.

Similarly to the way that :ref:`rejections-java` are handled the :ref:`-handleExceptions-java-` directive delegates the actual job
of converting an exception to its argument, an ``ExceptionHandler``.

An ``ExceptionHandler`` is a partial function, so it can choose which exceptions it would like to handle and
which not. Unhandled exceptions will simply continue to bubble up in the route structure.
At the root of the route tree any still unhandled exception will be dealt with by the top-level handler which always
handles *all* exceptions.

``Route.seal`` internally wraps its argument route with the :ref:`-handleExceptions-java-` directive in order to "catch" and
handle any exception.

So, if you'd like to customize the way certain exceptions are handled you need to write a custom ``ExceptionHandler``.
Once you have defined your custom ``ExceptionHandler`` you can supply it as argument to the :ref:`-handleExceptions-java-` directive.
That will apply your handler to the inner route given to that directive. 

Here is an example for wiring up a custom handler via :ref:`-handleExceptions-java-`:

TODO
