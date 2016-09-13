.. _akka-http-configuration-java:

Configuration
=============

Just like any other Akka module Akka HTTP is configured via `Typesafe Config`_.
Usually this means that you provide an ``application.conf`` which contains all the application-specific settings that
differ from the default ones provided by the reference configuration files from the individual Akka modules.

These are the relevant default configuration values for the Akka HTTP modules.

akka-http-core
~~~~~~~~~~~~~~

.. literalinclude:: ../../../../akka-http-core/src/main/resources/reference.conf
   :language: none


akka-http
~~~~~~~~~

.. literalinclude:: ../../../../akka-http/src/main/resources/reference.conf
   :language: none


The other Akka HTTP modules do not offer any configuration via `Typesafe Config`_.

.. _Typesafe Config: https://github.com/typesafehub/config
