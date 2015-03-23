
.. _microkernel-scala:

Microkernel
===========

The purpose of the Akka Microkernel is to offer a bundling mechanism so that you can distribute
an Akka application as a single payload, without the need to run in a Java Application Server or manually
having to create a launcher script.

.. warning:: Akka Microkernel will be deprecated and removed. It will be replaced by using an ordinary
   user defined main class and packaging with `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_
   or `Typesafe ConductR <http://typesafe.com/products/conductr>`_.

To run an application with the microkernel you need to create a Bootable class 
that handles the startup and shutdown the application.

The code for the Hello Kernel example (see the ``HelloKernel`` class for an example
of creating a Bootable):

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/src/main/scala/sample/kernel/hello/HelloKernel.scala

`sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ is the recommended tool for creating
distributions of Akka applications when using sbt.

Define sbt version in ``project/build.properties`` file: 

.. code-block:: none

    sbt.version=0.13.7

Add `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ in ``project/plugins.sbt`` file:

.. code-block:: none

   addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.0-RC1")

Use the package settings and specify the mainClass in ``build.sbt`` file:

.. includecode:: ../../../akka-samples/akka-sample-hello-kernel/build.sbt


.. note:: Use the ``JavaServerAppPackaging``. Don't use ``AkkaAppPackaging`` (previously named 
   ``packageArchetype.akka_application``, since it doesn't have the same flexibility and quality
   as the ``JavaServerAppPackaging``.

Use sbt task ``dist`` package the application.

To start the application (on a unix-based system):

.. code-block:: none

   cd target/universal/
   unzip hello-kernel-0.1.zip
   chmod u+x hello-kernel-0.1/bin/hello-kernel
   hello-kernel-0.1/bin/hello-kernel sample.kernel.hello.HelloKernel

Use ``Ctrl-C`` to interrupt and exit the microkernel.

On a Windows machine you can also use the ``bin\hello-kernel.bat`` script.


