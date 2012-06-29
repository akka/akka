Getting Started
===============

Prerequisites
-------------

Akka requires that you have `Java 1.6 <http://www.oracle.com/technetwork/java/javase/downloads/index.html>`_ or
later installed on you machine.

Getting Started Guides and Template Projects
--------------------------------------------

The best way to start learning Akka is to download the Typesafe Stack and either try out
the Akka Getting Started Tutorials or check out one of Akka Template Projects. Both comes
in several flavours depending on your development environment preferences.

- `Download Typesafe Stack <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html>`_
- `Getting Started Tutorials <http://typesafe.com/resources/getting-started/tutorials/index.html>`_
- `Template Projects <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html#template-projects-for-scala-akka-and-play>`_

Download
--------

There are several ways to download Akka. You can download it as part of the Typesafe Stack
(as described above). You can download the full distribution with microkernel, which includes
all modules. Or you can use a build tool like Maven or SBT to download dependencies from the
Akka Maven repository.

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

More information is available in the documentation of the microkernel
(:ref:`microkernel-java`, :ref:`microkernel-scala`).

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories. The Akka
Maven repository can be found at http://akka.io/realeses/ and Typesafe provides
http://repo.typesafe.com/typesafe/releases/ that proxies several other
repositories, including akka.io.

Using Akka with Maven
---------------------

The simplest way to get started with Akka and Maven is to check out the
`Akka/Maven template <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html#template-projects-for-scala-akka-and-play>`_
project.

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

The simplest way to get started with Akka and SBT is to check out the
`Akka/SBT template <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html#template-projects-for-scala-akka-and-play>`_
project.

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
