SLF4J
=====

This module is available in the 'akka-slf4j.jar'. It has one single dependency; the slf4j-api jar.

Logging trait
-------------

You can use the 'akka.event.slf4j.Logging' trait to mix in logging behavior into your classes and use the 'log' Logger member variable. But the preferred way is to use the event handler (see below).

Event Handler
-------------

This module also includes an SLF4J Event Handler that works with Akka's standar Event Handler. You enabled it in the 'event-handlers' element in akka.conf. Here you can also define the log level.

.. code-block:: ruby

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    event-handler-level = "DEBUG"
  }

Read more about how to use the event handler `here <http://doc.akka.io/event-handler>`_.

