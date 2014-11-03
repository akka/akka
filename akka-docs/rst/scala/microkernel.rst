
.. _microkernel-scala:

Microkernel
===================

The purpose of the Akka Microkernel is to offer a bundling mechanism so that you can distribute
an Akka application as a single payload, without the need to run in a Java Application Server or manually
having to create a launcher script.

The Akka Microkernel is included in the Akka download found at `downloads`_.

.. _downloads: http://akka.io/downloads

To run an application with the microkernel you need to create a Bootable class 
that handles the startup and shutdown the application.

The code for the Hello Kernel example (see the ``HelloKernel`` class for an example
of creating a Bootable):

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/src/main/scala/sample/kernel/hello/HelloKernel.scala


Add `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ to the plugins.sbt

.. code-block:: none

   addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "0.8.0-M2")

Use the package settings for ``akka_application``, and specify the mainClass in build.sbt

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/build.sbt


Use sbt task ``stage`` to generate the start script. Also you can use task ``universal:packageZipTarball`` to package the application.

To start the application (on a unix-based system):

.. code-block:: none

   ./target/universal/stage/bin/hello-kernel

Use ``Ctrl-C`` to interrupt and exit the microkernel.

On a Windows machine you can also use the ``target\universal\stage\bin\hello-kernel.bat`` script.


