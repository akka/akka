.. _logging-java:

################
 Logging (Java)
################


How to Log
==========

Create a ``LoggingAdapter`` and use the ``error``, ``warning``, ``info``, or ``debug`` methods,
as illustrated in this example:

.. includecode:: code/docs/event/LoggingDocTestBase.java
   :include: imports,my-actor

The first parameter to ``Logging.getLogger`` could also be any
:class:`LoggingBus`, specifically ``system.eventStream()``; in the demonstrated
case, the actor system’s address is included in the ``akkaSource``
representation of the log source (see `Logging Thread and Akka Source in MDC`_)
while in the second case this is not automatically done. The second parameter
to ``Logging.getLogger`` is the source of this logging channel.  The source
object is translated to a String according to the following rules:

  * if it is an Actor or ActorRef, its path is used
  * in case of a String it is used as is
  * in case of a class an approximation of its simpleName
  * and in all other cases the simpleName of its class

The log message may contain argument placeholders ``{}``, which will be
substituted if the log level is enabled. Giving more arguments as there are
placeholders results in a warning being appended to the log statement (i.e. on
the same line with the same severity). You may pass a Java array as the only
substitution argument to have its elements be treated individually:

.. includecode:: code/docs/event/LoggingDocTestBase.java#array

The Java :class:`Class` of the log source is also included in the generated
:class:`LogEvent`. In case of a simple string this is replaced with a “marker”
class :class:`akka.event.DummyClassForStringSources` in order to allow special
treatment of this case, e.g. in the SLF4J event listener which will then use
the string instead of the class’ name for looking up the logger instance to
use.

Auxiliary logging options
-------------------------

Akka has a couple of configuration options for very low level debugging, that makes most sense in
for developers and not for operations.

You almost definitely need to have logging set to DEBUG to use any of the options below:

.. code-block:: ruby

    akka {
      loglevel = DEBUG
    }

This config option is very good if you want to know what config settings are loaded by Akka:

.. code-block:: ruby

    akka {
      # Log the complete configuration at INFO level when the actor system is started.
      # This is useful when you are uncertain of what configuration is used.
      log-config-on-start = on
    }

If you want very detailed logging of all automatically received messages that are processed
by Actors:

.. code-block:: ruby

    akka {
      actor {
        debug {
          # enable DEBUG logging of all AutoReceiveMessages (Kill, PoisonPill and the like)
          autoreceive = on
        }
      }
    }

If you want very detailed logging of all lifecycle changes of Actors (restarts, deaths etc):

.. code-block:: ruby

    akka {
      actor {
        debug {
          # enable DEBUG logging of actor lifecycle changes
          lifecycle = on
        }
      }
    }

If you want very detailed logging of all events, transitions and timers of FSM Actors that extend LoggingFSM:

.. code-block:: ruby

    akka {
      actor {
        debug {
          # enable DEBUG logging of all LoggingFSMs for events, transitions and timers
          fsm = on
        }
      }
    }

If you want to monitor subscriptions (subscribe/unsubscribe) on the ActorSystem.eventStream:

.. code-block:: ruby

    akka {
      actor {
        debug {
          # enable DEBUG logging of subscription changes on the eventStream
          event-stream = on
        }
      }
    }

Auxiliary remote logging options
--------------------------------

If you want to see all messages that are sent through remoting at DEBUG log level:
(This is logged as they are sent by the transport layer, not by the Actor)

.. code-block:: ruby

    akka {
      remote {
        # If this is "on", Akka will log all outbound messages at DEBUG level, if off then they are not logged
        log-sent-messages = on
      }
    }

If you want to see all messages that are received through remoting at DEBUG log level:
(This is logged as they are received by the transport layer, not by any Actor)

.. code-block:: ruby

    akka {
      remote {
        # If this is "on", Akka will log all inbound messages at DEBUG level, if off then they are not logged
        log-received-messages = on
      }
    }

Also see the logging options for TestKit: :ref:`actor.logging`.


Event Handler
=============

Logging is performed asynchronously through an event bus. You can configure which event handlers that should
subscribe to the logging events. That is done using the 'event-handlers' element in the :ref:`configuration`.
Here you can also define the log level.

.. code-block:: ruby

  akka {
    # Event handlers to register at boot time (Logging$DefaultLogger logs to STDOUT)
    event-handlers = ["akka.event.Logging$DefaultLogger"]
    # Options: ERROR, WARNING, INFO, DEBUG
    loglevel = "DEBUG"
  }

The default one logs to STDOUT and is registered by default. It is not intended to be used for production. There is also an :ref:`slf4j-java`
event handler available in the 'akka-slf4j' module.

Example of creating a listener:

.. includecode:: code/docs/event/LoggingDocTestBase.java
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
(e.g. logback.xml).

.. code-block:: ruby

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    loglevel = "DEBUG"
  }

The SLF4J logger selected for each log event is chosen based on the
:class:`Class` of the log source specified when creating the
:class:`LoggingAdapter`, unless that was given directly as a string in which
case that string is used (i.e. ``LoggerFactory.getLogger(Class c)`` is used in
the first case and ``LoggerFactory.getLogger(String s)`` in the second).

.. note::

  Beware that the the actor system’s name is appended to a :class:`String` log
  source if the LoggingAdapter was created giving an :class:`ActorSystem` to
  the factory. If this is not intended, give a :class:`LoggingBus` instead as
  shown below:

.. code-block:: scala

  final LoggingAdapter log = Logging.getLogger(system.eventStream(), "my.nice.string");

Logging Thread and Akka Source in MDC
-------------------------------------

Since the logging is done asynchronously the thread in which the logging was performed is captured in
Mapped Diagnostic Context (MDC) with attribute name ``sourceThread``.
With Logback the thread name is available with ``%X{sourceThread}`` specifier within the pattern layout configuration::

  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%date{ISO8601} %-5level %logger{36} %X{sourceThread} - %msg%n</pattern>
    </encoder>
  </appender>

.. note::

  It will probably be a good idea to use the ``sourceThread`` MDC value also in
  non-Akka parts of the application in order to have this property consistently
  available in the logs.

Another helpful facility is that Akka captures the actor’s address when
instantiating a logger within it, meaning that the full instance identification
is available for associating log messages e.g. with members of a router. This
information is available in the MDC with attribute name ``akkaSource``::

  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%date{ISO8601} %-5level %logger{36} %X{akkaSource} - %msg%n</pattern>
    </encoder>
  </appender>

For more details on what this attribute contains—also for non-actors—please see
`How to Log`_.
