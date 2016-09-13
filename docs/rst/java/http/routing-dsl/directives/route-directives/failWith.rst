.. _-failWith-java-:

failWith
========

Description
-----------
Bubbles up the given error through the route structure where it is dealt with by the closest ``handleExceptions``
directive and its :class:`ExceptionHandler`.

``failWith`` explicitly raises an exception that gets bubbled up through the route structure to be picked up by the
nearest ``handleExceptions`` directive. Using ``failWith`` rather than simply throwing an exception enables the route
structure's :ref:`exception-handling-java` mechanism to deal with the exception even if the current route is executed
asynchronously on another thread (e.g. in a ``Future`` or separate actor).

If no ``handleExceptions`` is present above the respective location in the
route structure the top-level routing logic will handle the exception and translate it into a corresponding
``HttpResponse`` using the in-scope ``ExceptionHandler`` (see also the :ref:`exception-handling-java` chapter).

There is one notable special case: If the given exception is a ``RejectionError`` exception it is *not* bubbled up,
but rather the wrapped exception is unpacked and "executed". This allows the "tunneling" of a rejection via an
exception.


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java#failWith
