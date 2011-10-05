Configuration
=============

.. sidebar:: Contents

   .. contents:: :local:

.. _-Dakka.config:
.. _-Dakka.home:

Specifying the configuration file
---------------------------------

If you don't specify a configuration file then Akka uses default values, corresponding to the ``akka-reference.conf``
that you see below. You can specify your own configuration file to override any property in the reference config.
You only have to define the properties that differ from the default configuration.

The location of the config file to use can be specified in various ways:

* Define the ``-Dakka.config=...`` system property parameter with a file path to configuration file.

* Put an ``akka.conf`` file in the root of the classpath.

* Define the ``AKKA_HOME`` environment variable pointing to the root of the Akka
  distribution. The config is taken from the ``AKKA_HOME/config/akka.conf``. You
  can also point to the AKKA_HOME by specifying the ``-Dakka.home=...`` system
  property parameter.

If several of these ways to specify the config file are used at the same time the precedence is the order as given above,
i.e. you can always redefine the location with the ``-Dakka.config=...`` system property.


Defining the configuration file
-------------------------------

Here is the reference configuration file:

.. literalinclude:: ../../config/akka-reference.conf
   :language: none

A custom ``akka.conf`` might look like this::

  # In this file you can override any option defined in the 'akka-reference.conf' file.
  # Copy in all or parts of the 'akka-reference.conf' file and modify as you please.

  akka {
    event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]

    # Comma separated list of the enabled modules.
    enabled-modules = ["camel", "remote"]

    # These boot classes are loaded (and created) automatically when the Akka Microkernel boots up
    #     Can be used to bootstrap your application(s)
    #     Should be the FQN (Fully Qualified Name) of the boot class which needs to have a default constructor
    boot = ["sample.camel.Boot",
            "sample.myservice.Boot"]

    actor {
      throughput = 10  # Throughput for Dispatcher, set to 1 for complete fairness
    }

    remote {
      server {
        port = 2562    # The port clients should connect to. Default is 2552 (AKKA)
      }
    }
  }

.. _-Dakka.mode:

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
