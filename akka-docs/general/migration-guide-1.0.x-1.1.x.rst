
.. _migration-1.1:

################################
 Migration Guide 1.0.x to 1.1.x
################################

**Akka has now moved to Scala 2.9.x**


Akka Actor
==========

- is now dependency free, with the exception of the dependency on the
  ``scala-library.jar``

- does not bundle any logging anymore, but you can subscribe to events within
  Akka by registering an event handler on akka.event.EventHandler or by specifying
  the ``FQN`` of an Actor in the akka.conf under akka.event-handlers; there is an
  ``akka-slf4j`` module which still provides the Logging trait and a default
  ``SLF4J`` logger adapter.

  Don't forget to add a SLF4J backend though, we recommend:

  .. code-block:: scala

     lazy val logback = "ch.qos.logback" % "logback-classic" % "0.9.28" % "runtime"

- If you used HawtDispatcher and want to continue using it, you need to include
  akka-dispatcher-extras.jar from Akka Modules, in your akka.conf you need to
  specify: ``akka.dispatch.HawtDispatcherConfigurator`` instead of
  ``HawtDispatcher``

- FSM: the onTransition method changed from Function1 to PartialFunction; there
  is an implicit conversion for the precise types in place, but it may be
  necessary to add an underscore if you are passing an eta-expansion (using a
  method as function value).


Akka Typed Actor
================

- All methods starting with ``get*`` are deprecated and will be removed in post
  1.1 release.



Akka Remote
===========

- ``UnparsebleException`` has been renamed to
  ``CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException(exception,
  classname, message)``


Akka HTTP
=========

- akka.servlet.Initializer has been moved to ``akka-kernel`` to be able to have
  ``akka-http`` not depend on ``akka-remote``. If you don't want to use the class
  for kernel, just create your own version of ``akka.servlet.Initializer``, it's
  just a couple of lines of code and there are instructions in
  the :ref:`http-module` docs.

- akka.http.ListWriter has been removed in full, if you use it and want to keep
  using it, here's the code: `ListWriter`_.

- Jersey-server is now a "provided" dependency for ``akka-http``, so you'll need
  to add the dependency to your project, it's built against Jersey 1.3

.. _ListWriter: https://github.com/jboner/akka/blob/v1.0/akka-http/src/main/scala/akka/http/ListWriter.scala


Akka Testkit
============

- The TestKit moved into the akka-testkit subproject and correspondingly into the
  ``akka.testkit`` package.
