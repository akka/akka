
.. _microkernel-java:

Microkernel (Java)
==================

The purpose of the Akka Microkernel is to offer a bundling mechanism so that you can distribute
an Akka application as a single payload, without the need to run in a Java Application Server or manually
having to create a launcher script.

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

Use ``Ctrl-C`` to interrupt and exit the microkernel.

On a Windows machine you can also use the bin/akka.bat script.

The code for the Hello Kernel example (see the ``HelloKernel`` class for an example
of creating a Bootable):

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/src/main/java/sample/kernel/hello/java/HelloKernel.java


Distribution of microkernel application
---------------------------------------

To make a distribution package of the microkernel and your application the ``akka-sbt-plugin`` provides
``AkkaKernelPlugin``. It creates the directory structure, with jar files, configuration files and
start scripts.

To use the sbt plugin you define it in your ``project/plugins.sbt``:

.. includecode:: ../../../akka-sbt-plugin/sample/project/plugins.sbt

Then you add it to the settings of your ``project/Build.scala``. It is also important that you add the ``akka-kernel`` dependency.
This is an example of a complete sbt build file:

.. includecode:: ../../../akka-sbt-plugin/sample/project/Build.scala

Run the plugin with sbt::

  > dist
  > dist:clean

There are several settings that can be defined:

* ``outputDirectory`` - destination directory of the package, default ``target/dist``
* ``distJvmOptions`` - JVM parameters to be used in the start script
* ``configSourceDirs`` - Configuration files are copied from these directories, default ``src/config``, ``src/main/config``, ``src/main/resources``
* ``distMainClass`` - Kernel main class to use in start script
* ``libFilter`` - Filter of dependency jar files
* ``additionalLibs`` - Additional dependency jar files
