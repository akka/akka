.. _slf4j:

SLF4J
=====

This module is available in the 'akka-slf4j.jar'. It has one single dependency; the slf4j-api jar. In runtime you
also need a SLF4J backend, we recommend `Logback <http://logback.qos.ch/>`_:

  .. code-block:: scala

     lazy val logback = "ch.qos.logback" % "logback-classic" % "1.0.0" % "runtime"


Event Handler
-------------

This module includes a SLF4J Event Handler that works with Akka's standard Event Handler. You enabled it in the 'event-handlers' element in akka.conf. Here you can also define the log level.

.. code-block:: ruby

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    loglevel = "DEBUG"
  }

Read more about how to use the :ref:`event-handler`.

Logging thread in MDC
---------------------

Since the logging is done asynchronously the thread in which the logging was performed is captured in
Mapped Diagnostic Context (MDC) with attribute name ``sourceThread``.
With Logback the thread name is available with ``%X{sourceThread}`` specifier within the pattern layout configuration::

  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender"> 
    <layout> 
      <pattern>%date{ISO8601} %-5level %logger{36} %X{sourceThread} - %msg%n</pattern> 
    </layout> 
  </appender> 

  