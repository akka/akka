
.. _microkernel-java:

Microkernel
==================

The purpose of the Akka Microkernel is to offer a bundling mechanism so that you can distribute
an Akka application as a single payload, without the need to run in a Java Application Server or manually
having to create a launcher script.

The Akka Microkernel is included in the Akka download found at `downloads`_.

.. _downloads: http://akka.io/downloads

To run an application with the microkernel you need to create a Bootable class
that handles the startup and shutdown the application. An example is included below.

Put your application jar in the ``deploy`` directory and additional dependencies in the ``lib`` directory
to have them automatically loaded and placed on the classpath.

To start the kernel use the scripts in the ``bin`` directory, passing the boot
classes for your application. 

The start script adds ``config`` directory first in the classpath, followed by ``lib/*``.
It runs java with main class ``akka.kernel.Main`` and the supplied Bootable class as
argument.

Example command (on a unix-based system):

.. code-block:: none

   bin/akka sample.kernel.hello.HelloKernel

Use ``Ctrl-C`` to interrupt and exit the microkernel.

On a Windows machine you can also use the bin/akka.bat script.

The code for the Hello Kernel example (see the ``HelloKernel`` class for an example
of creating a Bootable):

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/src/main/java/sample/kernel/hello/java/HelloKernel.java


