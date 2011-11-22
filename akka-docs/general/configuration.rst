Configuration
=============

.. sidebar:: Contents

   .. contents:: :local:

.. _-Dakka.config:
.. _-Dakka.home:

Specifying the configuration file
---------------------------------

If you don't specify a configuration file then Akka uses default values, corresponding to the reference 
configuration files that you see below. You can specify your own configuration file to override any 
property in the reference config. You only have to define the properties that differ from the default 
configuration.

The location of the config file to use can be specified in various ways:

* Define the ``-Dakka.config=...`` system property parameter with a file path to configuration file.

* Put an ``akka.conf`` file in the root of the classpath.

* Define the ``AKKA_HOME`` environment variable pointing to the root of the Akka
  distribution. The config is taken from the ``AKKA_HOME/config/akka.conf``. You
  can also point to the AKKA_HOME by specifying the ``-Dakka.home=...`` system
  property parameter.

If several of these ways to specify the config file are used at the same time the precedence is the order as given above,
i.e. you can always redefine the location with the ``-Dakka.config=...`` system property.

You may also specify the configuration programmatically when instantiating the ``ActorSystem``.

.. includecode:: code/ConfigDocSpec.scala
   :include: imports,custom-config

The ``ConfigFactory`` provides several methods to parse the configuration from various sources.

Defining the configuration file
-------------------------------

Each Akka module has a reference configuration file with the default values.

*akka-actor:*

.. literalinclude:: ../../akka-actor/src/main/resources/akka-actor-reference.conf
   :language: none

*akka-remote:*

.. literalinclude:: ../../akka-remote/src/main/resources/akka-remote-reference.conf
   :language: none
   
*akka-serialization:*

.. literalinclude:: ../../akka-actor/src/main/resources/akka-serialization-reference.conf
   :language: none

*akka-testkit:*

.. literalinclude:: ../../akka-testkit/src/main/resources/akka-testkit-reference.conf
   :language: none

*akka-beanstalk-mailbox:*

.. literalinclude:: ../../akka-durable-mailboxes/akka-beanstalk-mailbox/src/main/resources/akka-beanstalk-mailbox-reference.conf
   :language: none

*akka-file-mailbox:*

.. literalinclude:: ../../akka-durable-mailboxes/akka-file-mailbox/src/main/resources/akka-file-mailbox-reference.conf
   :language: none

*akka-mongo-mailbox:*

.. literalinclude:: ../../akka-durable-mailboxes/akka-mongo-mailbox/src/main/resources/akka-mongo-mailbox-reference.conf
   :language: none

*akka-redis-mailbox:*

.. literalinclude:: ../../akka-durable-mailboxes/akka-redis-mailbox/src/main/resources/akka-redis-mailbox-reference.conf
   :language: none

*akka-zookeeper-mailbox:*

.. literalinclude:: ../../akka-durable-mailboxes/akka-zookeeper-mailbox/src/main/resources/akka-zookeeper-mailbox-reference.conf
   :language: none

A custom ``akka.conf`` might look like this::

  # In this file you can override any option defined in the reference files.
  # Copy in parts of the reference files and modify as you please.

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
    loglevel        = DEBUG  # Options: ERROR, WARNING, INFO, DEBUG
                             # this level is used by the configured loggers (see "event-handlers") as soon
                             # as they have been started; before that, see "stdout-loglevel"
    stdout-loglevel = DEBUG  # Loglevel for the very basic logger activated during AkkaApplication startup

    # Comma separated list of the enabled modules.
    enabled-modules = ["camel", "remote"]

    # These boot classes are loaded (and created) automatically when the Akka Microkernel boots up
    #     Can be used to bootstrap your application(s)
    #     Should be the FQN (Fully Qualified Name) of the boot class which needs to have a default constructor
    boot = ["sample.camel.Boot",
            "sample.myservice.Boot"]

    actor {
      default-dispatcher {
        throughput = 10  # Throughput for default Dispatcher, set to 1 for complete fairness
      }
    }

    remote {
      server {
        port = 2562    # The port clients should connect to. Default is 2552 (AKKA)
      }
    }
  }

.. _-Dakka.mode:

Config file format
------------------

The configuration file syntax is described in the `HOCON <https://github.com/havocp/config/blob/master/HOCON.md>`_
specification. Note that it supports three formats; conf, json, and properties. 

Specifying files for different modes
------------------------------------

You can use different configuration files for different purposes by specifying a mode option, either as
``-Dakka.mode=...`` system property or as ``AKKA_MODE=...`` environment variable. For example using DEBUG log level
when in development mode. Run with ``-Dakka.mode=dev`` and place the following ``akka.dev.conf`` in the root of
the classpath.

akka.dev.conf:

::

  akka {
    event-handler-level = "DEBUG"
  }

The mode option works in the same way when using configuration files in ``AKKA_HOME/config/`` directory.

The mode option is not used when specifying the configuration file with ``-Dakka.config=...`` system property.

Including files
---------------

Sometimes it can be useful to include another configuration file, for example if you have one ``akka.conf`` with all
environment independent settings and then override some settings for specific modes.

akka.dev.conf:

::

  include "akka.conf"

  akka {
    event-handler-level = "DEBUG"
  }

.. _-Dakka.output.config.source:

Showing Configuration Source
----------------------------

If the system property ``akka.output.config.source`` is set to anything but
null, then the source from which Akka reads its configuration is printed to the
console during application startup.

Summary of System Properties
----------------------------

* :ref:`akka.home <-Dakka.home>` (``AKKA_HOME``): where Akka searches for configuration
* :ref:`akka.config <-Dakka.config>`: explicit configuration file location
* :ref:`akka.mode <-Dakka.mode>` (``AKKA_MODE``): modify configuration file name for multiple profiles
* :ref:`akka.output.config.source <-Dakka.output.config.source>`: whether to print configuration source to console
