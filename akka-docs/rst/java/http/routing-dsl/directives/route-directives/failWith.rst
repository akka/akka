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
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
