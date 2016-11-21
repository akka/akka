.. _deployment-scenarios:

###################################
 Use-case and Deployment Scenarios
###################################

How can I use and deploy Akka?
==============================

Akka can be used in different ways:

- As a library: used as a regular JAR on the classpath and/or in a web app, to
  be put into ``WEB-INF/lib``

- Package with `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_

- Package and deploy using `Lightbend ConductR <http://www.lightbend.com/products/conductr>`_.


Native Packager
===============

`sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ is a tool for creating
distributions of any type of application, including an Akka applications.

Define sbt version in ``project/build.properties`` file: 

.. code-block:: none

    sbt.version=0.13.7

Add `sbt-native-packager <https://github.com/sbt/sbt-native-packager>`_ in ``project/plugins.sbt`` file:

.. code-block:: none

   addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.0-RC1")

Use the package settings and optionally specify the mainClass in ``build.sbt`` file:

.. includecode:: ../../../akka-samples/akka-sample-main-scala/build.sbt


.. note:: Use the ``JavaServerAppPackaging``. Don't use the deprecated ``AkkaAppPackaging`` (previously named 
   ``packageArchetype.akka_application``), since it doesn't have the same flexibility and quality
   as the ``JavaServerAppPackaging``.

Use sbt task ``dist`` package the application.

To start the application (on a unix-based system):

.. code-block:: none

   cd target/universal/
   unzip akka-sample-main-scala-2.4.14.zip
   chmod u+x akka-sample-main-scala-2.4.14/bin/akka-sample-main-scala
   akka-sample-main-scala-2.4.14/bin/akka-sample-main-scala sample.hello.Main

Use ``Ctrl-C`` to interrupt and exit the application.

On a Windows machine you can also use the ``bin\akka-sample-main-scala.bat`` script.


In a Docker container
=====================
You can use both Akka remoting and Akka Cluster inside of Docker containers. But note
that you will need to take special care with the network configuration when using Docker,
described here: :ref:`remote-configuration-nat`

For an example of how to set up a project using Akka Cluster and Docker take a look at the
`"akka-docker-cluster" activator template`__.

__ https://www.lightbend.com/activator/template/akka-docker-cluster

