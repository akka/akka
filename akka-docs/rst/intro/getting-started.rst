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

- `Download Typesafe Stack <http://typesafe.com/stack/download>`_
- `Getting Started Tutorials <http://typesafe.com/resources/getting-started>`_
- `Template Projects <http://typesafe.com/stack/download#template>`_

Download
--------

There are several ways to download Akka. You can download it as part of the Typesafe Stack
(as described above). You can download the full distribution with microkernel, which includes
all modules. Or you can use a build tool like Maven or SBT to download dependencies from the
Akka Maven repository.

Modules
-------

Akka is very modular and consists of several JARs containing different features.

- ``akka-actor`` -- Classic Actors, Typed Actors, IO Actor etc.
- ``akka-remote`` -- Remote Actors
- ``akka-testkit`` -- Toolkit for testing Actor systems
- ``akka-kernel`` -- Akka microkernel for running a bare-bones mini application server
- ``akka-transactor`` -- Transactors - transactional actors, integrated with Scala STM
- ``akka-agent`` -- Agents, integrated with Scala STM
- ``akka-camel`` -- Apache Camel integration
- ``akka-zeromq`` -- ZeroMQ integration
- ``akka-slf4j`` -- SLF4J Event Handler Listener
- ``akka-filebased-mailbox`` -- Akka durable mailbox (find more among community projects)

The filename of the actual JAR is for example ``@jarName@`` (and analog for
the other modules).

How to see the JARs dependencies of each Akka module is described in the
:ref:`dependencies` section.

Using a release distribution
----------------------------

Download the release you need from http://typesafe.com/stack/downloads/akka and unzip it.

Using a snapshot version
------------------------

The Akka nightly snapshots are published to http://repo.akka.io/snapshots/ and are
versioned with both ``SNAPSHOT`` and timestamps. You can choose a timestamped
version to work with and can decide when to update to a newer version. The Akka
snapshots repository is also proxied through http://repo.typesafe.com/typesafe/snapshots/
which includes proxies for several other repositories that Akka modules depend on.

Microkernel
-----------

The Akka distribution includes the microkernel. To run the microkernel put your
application jar in the ``deploy`` directory and use the scripts in the ``bin``
directory.

More information is available in the documentation of the
:ref:`microkernel-scala` / :ref:`microkernel-java`.

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories. The Akka
Maven repository can be found at http://repo.akka.io/releases/ and Typesafe provides
http://repo.typesafe.com/typesafe/releases/ that proxies several other
repositories, including akka.io.

Using Akka with Maven
---------------------

The simplest way to get started with Akka and Maven is to check out the
`Akka/Maven template <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html#template-projects-for-scala-akka-and-play>`_
project.

Since Akka is published to Maven Central (for versions since 2.1-M2), is it
enough to add the Akka dependencies to the POM. For example, here is the
dependency for akka-actor:

.. code-block:: xml

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

**Note**: for snapshot versions both ``SNAPSHOT`` and timestamped versions are published.


Using Akka with SBT
-------------------

The simplest way to get started with Akka and SBT is to check out the
`Akka/SBT template <http://typesafe.com/resources/getting-started/typesafe-stack/downloading-installing.html#template-projects-for-scala-akka-and-play>`_
project.

Summary of the essential parts for using Akka with SBT:

SBT installation instructions on `https://github.com/harrah/xsbt/wiki/Setup <https://github.com/harrah/xsbt/wiki/Setup>`_

``build.sbt`` file:

.. parsed-literal::

    name := "My Project"

    version := "1.0"

    scalaVersion := "@scalaVersion@"

    resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

    libraryDependencies +=
      "com.typesafe.akka" %% "akka-actor" % "@version@" @crossString@


Using Akka with Eclipse
-----------------------

Setup SBT project and then use `sbteclipse <https://github.com/typesafehub/sbteclipse>`_ to generate a Eclipse project.

Using Akka with IntelliJ IDEA
-----------------------------

Setup SBT project and then use `sbt-idea <https://github.com/mpeltonen/sbt-idea>`_ to generate a IntelliJ IDEA project.

Using Akka with NetBeans
------------------------

Setup SBT project and then use `sbt-netbeans-plugin <https://github.com/remeniuk/sbt-netbeans-plugin>`_ to generate a NetBeans project.

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

