.. _logging-java:

################
 Logging (Java)
################

.. sidebar:: Contents

   .. contents:: :local:

How to Log
==========

Create a ``LoggingAdapter`` and use the ``error``, ``warning``, ``info``, or ``debug`` methods,
as illustrated in this example:

.. includecode:: code/akka/docs/event/LoggingDocTestBase.java
   :include: imports,my-actor

The second parameter to the ``Logging.getLogger`` is the source of this logging channel.
The source object is translated to a String according to the following rules:

  * if it is an Actor or ActorRef, its path is used
  * in case of a String it is used as is
  * in case of a class an approximation of its simpleName
  * and in all other cases the simpleName of its class

The log message may contain argument placeholders ``{}``, which will be substituted if the log level 
is enabled.

Event Handler
=============

Logging is performed asynchronously through an event bus. You can configure which event handlers that should 
subscribe to the logging events. That is done using the 'event-handlers' element in the :ref:`configuration`. 
Here you can also define the log level.

.. code-block:: ruby

  akka {
    # Event handlers to register at boot time (Logging$DefaultLogger logs to STDOUT)
    event-handlers = ["akka.event.Logging$DefaultLogger"]
    loglevel = "DEBUG" # Options: ERROR, WARNING, INFO, DEBUG
  }

The default one logs to STDOUT and is registered by default. It is not intended to be used for production. There is also an :ref:`slf4j-java` 
event handler available in the 'akka-slf4j' module.

Example of creating a listener:

.. includecode:: code/akka/docs/event/LoggingDocTestBase.java
   :include: imports,imports-listener,my-event-listener 


.. _slf4j-java:

SLF4J
=====

Akka provides an event handler for `SL4FJ <http://www.slf4j.org/>`_. This module is available in the 'akka-slf4j.jar'. 
It has one single dependency; the slf4j-api jar. In runtime you also need a SLF4J backend, we recommend `Logback <http://logback.qos.ch/>`_:

  .. code-block:: xml

     <dependency>
       <groupId>ch.qos.logback</groupId>
       <artifactId>logback-classic</artifactId>
       <version>1.0.0</version>
       <scope>runtime</scope>
     </dependency>

You need to enable the Slf4jEventHandler in the 'event-handlers' element in 
the :ref:`configuration`. Here you can also define the log level of the event bus. 
More fine grained log levels can be defined in the configuration of the SLF4J backend
(e.g. logback.xml). The String representation of the source object that is used when 
creating the ``LoggingAdapter`` correspond to the name of the SL4FJ logger.

.. code-block:: ruby

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    loglevel = "DEBUG"
  }

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

