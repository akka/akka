Configuration
=============

Specifying the configuration file
---------------------------------

If you don't specify a configuration file then Akka uses default values. If
you want to override these then you should edit the ``akka.conf`` file in the
``AKKA_HOME/config`` directory. This config inherits from the
``akka-reference.conf`` file that you see below. Use your ``akka.conf`` to override
any property in the reference config.

The config can be specified in various ways:

* Define the ``-Dakka.config=...`` system property option

* Put an ``akka.conf`` file on the classpath

* Define the ``AKKA_HOME`` environment variable pointing to the root of the Akka
  distribution. The config is taken from the ``AKKA_HOME/config`` directory. You
  can also point to the AKKA_HOME by specifying the ``-Dakka.home=...`` system
  property option.


Defining the configuration file
-------------------------------

Here is the reference configuration file:

.. literalinclude:: ../../config/akka-reference.conf
   :language: none
