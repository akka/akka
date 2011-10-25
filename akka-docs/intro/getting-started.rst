Getting Started
===============

.. sidebar:: Contents

   .. contents:: :local:

The best way to start learning Akka is to try the Getting Started Tutorial,
which comes in several flavours depending on you development environment
preferences:

- :ref:`getting-started-first-java` for Java development, either

  - as standalone project, running from the command line,
  - or as Maven project and running it from within Maven

- :ref:`getting-started-first-scala` for Scala development, either

  - as standalone project, running from the command line,
  - or as SBT (Simple Build Tool) project and running it from within SBT

- :ref:`getting-started-first-scala-eclipse` for Scala development with Eclipse

The Getting Started Tutorial describes everything you need to get going, and you
don't need to read the rest of this page if you study the tutorial. For later
look back reference this page describes the essential parts for getting started
with different development environments.

Prerequisites
-------------

Akka requires that you have `Java 1.6 <http://www.oracle.com/technetwork/java/javase/downloads/index.html>`_ or
later installed on you machine.

Download
--------

There are several ways to download Akka. You can download the full distribution
with microkernel, which includes all modules. You can download just the core
distribution or just the actors distribution. Or you can use a build tool like
Maven or sbt to download dependencies from the Akka Maven repository.

Modules
-------

Akka is very modular and has many JARs for containing different features.

- ``akka-actor-2.0-SNAPSHOT.jar`` -- Standard Actors
- ``akka-typed-actor-2.0-SNAPSHOT.jar`` -- Typed Actors
- ``akka-remote-2.0-SNAPSHOT.jar`` -- Remote Actors
- ``akka-stm-2.0-SNAPSHOT.jar`` -- STM (Software Transactional Memory), transactors and transactional datastructures
- ``akka-http-2.0-SNAPSHOT.jar`` -- Akka Mist for continuation-based asynchronous HTTP and also Jersey integration
- ``akka-slf4j-2.0-SNAPSHOT.jar`` -- SLF4J Event Handler Listener
- ``akka-testkit-2.0-SNAPSHOT.jar`` -- Toolkit for testing Actors
- ``akka-camel-2.0-SNAPSHOT.jar`` -- Apache Camel Actors integration (it's the best way to have your Akka application communicate with the rest of the world)
- ``akka-camel-typed-2.0-SNAPSHOT.jar`` -- Apache Camel Typed Actors integration
- ``akka-spring-2.0-SNAPSHOT.jar`` -- Spring framework integration
- ``akka-kernel-2.0-SNAPSHOT.jar`` -- Akka microkernel for running a bare-bones mini application server (embeds Jetty etc.)

How to see the JARs dependencies of each Akka module is described in the
:ref:`dependencies` section. Worth noting is that ``akka-actor`` has zero
external dependencies (apart from the ``scala-library.jar`` JAR).

Using a release distribution
----------------------------

Download the release you need, Akka Actors, Akka Core, or Akka Microkernel, from
http://akka.io/downloads and unzip it.

Using a snapshot version
------------------------

The Akka nightly snapshots are published to
http://repo.typesafe.com/typesafe/maven-timestamps/ and are versioned with a
timestamp. You need to choose a timestamped version to work with and can decide
when to update to a newer version.

Microkernel
^^^^^^^^^^^

The Akka Modules distribution includes the microkernel. To run the microkernel:

* Set the AKKA_HOME environment variable to the root of the Akka distribution.
* To start the kernel use the scripts in the ``bin`` directory and deploy all samples applications from ``./deploy`` dir.

More information is available in the documentation of the :ref:`microkernel`.

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories. The Akka
Maven repository can be found at http://akka.io/repository/ and Typesafe provides
http://repo.typesafe.com/typesafe/releases/ that proxies several other
repositories, including akka.io.

Using Akka with Maven
---------------------

Information about how to use Akka with Maven, including how to create an Akka
Maven project from scratch, can be found in the
:ref:`getting-started-first-java`.

Summary of the essential parts for using Akka with Maven:

1) Add this repository to your ``pom.xml``:

.. code-block:: xml

  <repository>
    <id>typesafe</id>
    <name>Typesafe Repository</name>
    <url>http://repo.typesafe.com/typesafe/releases/</url>
  </repository>

2) Add the Akka dependencies. For example, here is the dependency for Akka Actor 2.0-SNAPSHOT:

.. code-block:: xml

  <dependency>
    <groupId>se.scalablesolutions.akka</groupId>
    <artifactId>akka-actor</artifactId>
    <version>2.0-SNAPSHOT</version>
  </dependency>

**Note**: for snapshot versions akka uses specific timestamped versions.


Using Akka with SBT
-------------------

Information about how to use Akka with SBT, including how to create an Akka SBT project from scratch,
can be found in the :ref:`getting-started-first-scala`.

Summary of the essential parts for using Akka with SBT:

SBT installation instructions on `https://github.com/harrah/xsbt/wiki/Setup <https://github.com/harrah/xsbt/wiki/Setup>`_

``build.sbt`` file::

    name := "My Project"

    version := "1.0"

    scalaVersion := "2.9.1"

    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

    libraryDependencies += "se.scalablesolutions.akka" % "akka-actor" % "2.0-SNAPSHOT"


Using Akka with Eclipse
-----------------------

Information about how to use Akka with Eclipse, including how to create an Akka Eclipse project from scratch,
can be found in the :ref:`getting-started-first-scala-eclipse`.

Setup SBT project and then use `sbteclipse <https://github.com/typesafehub/sbteclipse>`_ to generate Eclipse project.

Using Akka with IntelliJ IDEA
-----------------------------

Setup SBT project and then use `sbt-idea <https://github.com/mpeltonen/sbt-idea>`_ to generate IntelliJ IDEA project.

Build from sources
------------------

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/jboner/akka>`_
* Akka Modules: clone the Akka Modules repository from `<http://github.com/jboner/akka-modules>`_

Continue reading the page on :ref:`building-akka`

Need help?
----------

If you have questions you can get help on the `Akka Mailing List <http://groups.google.com/group/akka-user>`_.

You can also ask for `commercial support <http://typesafe.com>`_.

Thanks for being a part of the Akka community.
