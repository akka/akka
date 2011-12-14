
.. _microkernel:

#############
 Microkernel
#############

The Akka Microkernel is included in the Akka download found at `downloads`_.

.. _downloads: http://akka.io/downloads

To run an application with the microkernel you need to create a Bootable class
that handles the startup and shutdown the application. An example is included below.

Put your application jar in the ``deploy`` directory to have it automatically
loaded.

To start the kernel use the scripts in the ``bin`` directory, passing the boot
classes for your application.

There is a simple example of an application setup for running with the
microkernel included in the akka download. This can be run with the following
command (on a unix-based system):

.. code-block:: none

   bin/akka sample.kernel.hello.HelloKernel

Use Ctrl-C to interrupt and exit the microkernel.

On a Windows machine you can also use the bin/akka.bat script.

The code for the Hello Kernel example (see the HelloKernel class for an example
of creating a Bootable):

.. includecode:: ../../akka-samples/akka-sample-hello-kernel/src/main/scala/sample/kernel/hello/HelloKernel.scala
