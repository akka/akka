.. _slf4j:

SLF4J
=====

This module is available in the 'akka-slf4j.jar'. It has one single dependency; the slf4j-api jar. In runtime you
also need a SLF4J backend, we recommend:

  .. code-block:: scala

     lazy val logback = "ch.qos.logback" % "logback-classic" % "0.9.28" % "runtime"


Event Handler
-------------

This module includes a SLF4J Event Handler that works with Akka's standard Event Handler. You enabled it in the 'event-handlers' element in akka.conf. Here you can also define the log level.

.. code-block:: ruby

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    event-handler-level = "DEBUG"
  }

Read more about how to use the :ref:`event-handler`.

