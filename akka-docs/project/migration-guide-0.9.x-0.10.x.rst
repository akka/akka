Migration Guide 0.9.x to 0.10.x
===============================

Module akka-camel
-----------------

The following list summarizes the breaking changes since Akka 0.9.1.

* CamelService moved from package se.scalablesolutions.akka.camel.service one level up to se.scalablesolutions.akka.camel.
* CamelService.newInstance removed. For starting and stopping a CamelService, applications should use

  * CamelServiceManager.startCamelService and
  * CamelServiceManager.stopCamelService.

* Existing def receive = produce method definitions from Producer implementations must be removed (resolves compile error: method receive needs override modifier).
* The Producer.async method and the related Sync trait have been removed. This is now fully covered by Camel's `asynchronous routing engine <http://camel.apache.org/asynchronous-processing.html>`_.
* @consume annotation can not placed any longer on actors (i.e. on type-level), only on typed actor methods. Consumer actors must mixin the Consumer trait.
* @consume annotation moved to package se.scalablesolutions.akka.camel.

Logging
-------

We've switched to Logback (SLF4J compatible) for the logging, if you're having trouble seeing your log output you'll need to make sure that there's a logback.xml available on the classpath or you'll need to specify the location of the logback.xml file via the system property, ex: -Dlogback.configurationFile=/path/to/logback.xml

Configuration
-------------

* The configuration is now JSON-style (see below).
* Now you can define the time-unit to be used throughout the config file:

.. code-block:: ruby

  akka {
    version = "0.10"
    time-unit = "seconds"      # default timeout time unit for all timeout properties throughout the config

    actor {
      timeout = 5              # default timeout for future based invocations
      throughput = 5           # default throughput for Dispatcher
    }
    ...
  }

RemoteClient events
-------------------

All events now has a reference to the RemoteClient instance instead of 'hostname' and 'port'. This is more flexible. Enables simpler reconnecting etc.
