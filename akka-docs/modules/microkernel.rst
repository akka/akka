
.. _microkernel:

#############
 Microkernel
#############


Run the microkernel
===================

To start the kernel use the scripts in the ``bin`` directory.

All services are configured in the ``config/akka.conf`` configuration file. See
the Akka documentation on Configuration for more details. Services you want to
be started up automatically should be listed in the list of ``boot`` classes in
the configuration.

Put your application in the ``deploy`` directory.


Akka Home
---------

Note that the microkernel needs to know where the Akka home is (the base
directory of the microkernel). The above scripts do this for you. Otherwise, you
can set Akka home by:

* Specifying the ``AKKA_HOME`` environment variable

* Specifying the ``-Dakka.home`` java option


.. _hello-microkernel:

Hello Microkernel
=================

There is a very simple Akka Mist sample project included in the microkernel
``deploy`` directory. Start the microkernel with the start script and then go to
http://localhost:9998 to say Hello to the microkernel.
