Getting Started
===============

The best way to start learning Akka is to try the Getting Started Tutorial,
which comes in several flavours depending on you development environment
preferences:

- :ref:`getting-started-first-java` for Java development, either

  - as standalone project, running from the command line,
  - or as Maven project and running it from within Maven

- :ref:`getting-started-first-scala` for Scala development, either

  - as standalone project, running from the command line,
  - or as SBT (Simple Build Tool) project and running it from within SBT

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
with microkernel, which includes all modules. Or you can use a build tool like
Maven or sbt to download dependencies from the Akka Maven repository.

Modules
-------

Akka is very modular and has many JARs for containing different features.

- ``akka-actor-2.0.2-SNAPSHOT.jar`` -- Standard Actors, Typed Actors and much more
- ``akka-remote-2.0.2-SNAPSHOT.jar`` -- Remote Actors
- ``akka-slf4j-2.0.2-SNAPSHOT.jar`` -- SLF4J Event Handler Listener
- ``akka-testkit-2.0.2-SNAPSHOT.jar`` -- Toolkit for testing Actors
- ``akka-kernel-2.0.2-SNAPSHOT.jar`` -- Akka microkernel for running a bare-bones mini application server
- ``akka-<storage-system>-mailbox-2.0.2-SNAPSHOT.jar`` -- Akka durable mailboxes

How to see the JARs dependencies of each Akka module is described in the
:ref:`dependencies` section. Worth noting is that ``akka-actor`` has zero
external dependencies (apart from the ``scala-library.jar`` JAR).

Using a release distribution
----------------------------

Download the release you need from http://akka.io/downloads and unzip it.

Using a snapshot version
------------------------

The Akka nightly snapshots are published to http://akka.io/snapshots/ and are
versioned with both ``SNAPSHOT`` and timestamps. You can choose a timestamped
version to work with and can decide when to update to a newer version. The Akka
snapshots repository is also proxied through http://repo.typesafe.com/typesafe/snapshots/
which includes proxies for several other repositories that Akka modules depend on.

Microkernel
-----------

The Akka distribution includes the microkernel. To run the microkernel put your
application jar in the ``deploy`` directory and use the scripts in the ``bin``
directory.

More information is available in the documentation of the :ref:`microkernel`.

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories. The Akka
Maven repository can be found at http://akka.io/realeses/ and Typesafe provides
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

2) Add the Akka dependencies. For example, here is the dependency for Akka Actor 2.0.2-SNAPSHOT:

.. code-block:: xml

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor</artifactId>
    <version>2.0.2-SNAPSHOT</version>
  </dependency>

**Note**: for snapshot versions both ``SNAPSHOT`` and timestamped versions are published.


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

    libraryDependencies += "com.typesafe.akka" % "akka-actor" % "2.0.2-SNAPSHOT"


Using Akka with Eclipse
-----------------------

Setup SBT project and then use `sbteclipse <https://github.com/typesafehub/sbteclipse>`_ to generate Eclipse project.

Using Akka with IntelliJ IDEA
-----------------------------

Setup SBT project and then use `sbt-idea <https://github.com/mpeltonen/sbt-idea>`_ to generate IntelliJ IDEA project.

Build from sources
------------------

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/akka/akka>`_

Continue reading the page on :ref:`building-akka`

Need help?
----------

If you have questions you can get help on the `Akka Mailing List <http://groups.google.com/group/akka-user>`_.

You can also ask for `commercial support <http://typesafe.com>`_.

Thanks for being a part of the Akka community.
