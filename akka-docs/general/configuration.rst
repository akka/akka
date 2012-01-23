.. _configuration:

Configuration
=============

.. sidebar:: Contents

   .. contents:: :local:


Specifying the configuration file
---------------------------------

If you don't specify a configuration file then Akka uses default values, corresponding to the reference
configuration files that you see below. You can specify your own configuration file to override any
property in the reference config. You only have to define the properties that differ from the default
configuration.

By default the ``ConfigFactory.load`` method is used, which will load all ``application.conf`` (and
``application.json`` and ``application.properties``) from the root of the classpath, if they exists.
It uses ``ConfigFactory.defaultOverrides``, i.e. system properties, before falling back to
application and reference configuration.

Note that *all* ``application.{conf,json,properties}`` classpath resources, from all directories and
jar files, are loaded and merged. Therefore it is a good practice to define separate sub-trees in the
configuration for each actor system, and grab the specific configuration when instantiating the ActorSystem.

::

  myapp1 {
    akka.loglevel = WARNING
  }
  myapp2 {
    akka.loglevel = ERROR
  }

.. code-block:: scala

  val app1 = ActorSystem("MyApp1", ConfigFactory.load.getConfig("myapp1"))
  val app2 = ActorSystem("MyApp2", ConfigFactory.load.getConfig("myapp2"))

If the system properties ``config.resource``, ``config.file``, or ``config.url`` are set, then the
classpath resource, file, or URL specified in those properties will be used rather than the default
``application.{conf,json,properties}`` classpath resources. Note that classpath resource names start
with ``/``. ``-Dconfig.resource=/dev.conf`` will load the ``dev.conf`` from the root of the classpath.

You may also specify and parse the configuration programmatically in other ways when instantiating
the ``ActorSystem``.

.. includecode:: code/akka/docs/config/ConfigDocSpec.scala
   :include: imports,custom-config

The ``ConfigFactory`` provides several methods to parse the configuration from various sources.

Defining the configuration file
-------------------------------

Each Akka module has a reference configuration file with the default values.

akka-actor
~~~~~~~~~~

.. literalinclude:: ../../akka-actor/src/main/resources/reference.conf
   :language: none

akka-remote
~~~~~~~~~~~

.. literalinclude:: ../../akka-remote/src/main/resources/reference.conf
   :language: none

akka-testkit
~~~~~~~~~~~~

.. literalinclude:: ../../akka-testkit/src/main/resources/reference.conf
   :language: none

akka-beanstalk-mailbox
~~~~~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-beanstalk-mailbox/src/main/resources/reference.conf
   :language: none

akka-file-mailbox
~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-file-mailbox/src/main/resources/reference.conf
   :language: none

akka-mongo-mailbox
~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-mongo-mailbox/src/main/resources/reference.conf
   :language: none

akka-redis-mailbox
~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-redis-mailbox/src/main/resources/reference.conf
   :language: none

akka-zookeeper-mailbox
~~~~~~~~~~~~~~~~~~~~~~

.. literalinclude:: ../../akka-durable-mailboxes/akka-zookeeper-mailbox/src/main/resources/reference.conf
   :language: none

Custom application.conf
-----------------------

A custom ``application.conf`` might look like this::

  # In this file you can override any option defined in the reference files.
  # Copy in parts of the reference files and modify as you please.

  akka {

    # Event handlers to register at boot time (Logging$DefaultLogger logs to STDOUT)
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]

    # Log level used by the configured loggers (see "event-handlers") as soon
    # as they have been started; before that, see "stdout-loglevel"
    # Options: ERROR, WARNING, INFO, DEBUG
    loglevel = DEBUG

    # Log level for the very basic logger activated during AkkaApplication startup
    # Options: ERROR, WARNING, INFO, DEBUG
    stdout-loglevel = DEBUG

    actor {
      default-dispatcher {
        # Throughput for default Dispatcher, set to 1 for as fair as possible
        throughput = 10
      }
    }

    remote {
      server {
        # The port clients should connect to. Default is 2552 (AKKA)
        port = 2562
      }
    }
  }


Config file format
------------------

The configuration file syntax is described in the `HOCON <https://github.com/typesafehub/config/blob/master/HOCON.md>`_
specification. Note that it supports three formats; conf, json, and properties.


Including files
---------------

Sometimes it can be useful to include another configuration file, for example if you have one ``application.conf`` with all
environment independent settings and then override some settings for specific environments.

Specifying system property with ``-Dconfig.resource=/dev.conf`` will load the ``dev.conf`` file, which includes the ``application.conf``

dev.conf:

::

  include "application"

  akka {
    loglevel = "DEBUG"
  }

More advanced include and substitution mechanisms are explained in the `HOCON <https://github.com/typesafehub/config/blob/master/HOCON.md>`_
specification.


.. _-Dakka.logConfigOnStart:

Logging of Configuration
------------------------

If the system or config property ``akka.logConfigOnStart`` is set to ``on``, then the
complete configuration at INFO level when the actor system is started. This is useful
when you are uncertain of what configuration is used.

If in doubt, you can also easily and nicely inspect configuration objects
before or after using them to construct an actor system:

.. code-block:: scala

  Welcome to Scala version 2.9.1.final (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_27).
  Type in expressions to have them evaluated.
  Type :help for more information.

  scala> import com.typesafe.config._
  import com.typesafe.config._

  scala> ConfigFactory.parseString("a.b=12")
  res0: com.typesafe.config.Config = Config(SimpleConfigObject({"a" : {"b" : 12}}))

  scala> res0.root.render
  res1: java.lang.String =
  {
      # String: 1
      "a" : {
          # String: 1
          "b" : 12
      }
  }

The comments preceding every item give detailed information about the origin of
the setting (file & line number) plus possible comments which were present,
e.g. in the reference configuration. The settings as merged with the reference
and parsed by the actor system can be displayed like this:

.. code-block:: java

  final ActorSystem system = ActorSystem.create();
  println(system.settings());
  // this is a shortcut for system.settings().config().root().render()


Application specific settings
-----------------------------

The configuration can also be used for application specific settings.
A good practice is to place those settings in an Extension, as described in:

 * Scala API: :ref:`extending-akka-scala.settings`
 * Java API: :ref:`extending-akka-java.settings`
