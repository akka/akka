Getting Started
===============

Prerequisites
-------------

Akka requires that you have `Java 8 <http://www.oracle.com/technetwork/java/javase/downloads/index.html>`_ or
later installed on your machine.

`Lightbend Inc. <http://www.lightbend.com>`_ provides a commercial build of Akka and related projects such as Scala or Play
as part of the `Reactive Platform <http://www.lightbend.com/products/lightbend-reactive-platform>`_ which is made available
for Java 6 in case your project can not upgrade to Java 8 just yet. It also includes additional commercial features or libraries.

Getting Started Guides and Template Projects
--------------------------------------------

The best way to start learning Akka is to download `Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
and try out one of Akka Template Projects.

Download
--------

There are several ways to download Akka. You can download it as part of the Lightbend Platform
(as described above). You can download the full distribution, which includes all modules. 
Or you can use a build tool like Maven or SBT to download dependencies from the Akka Maven repository.

Modules
-------

Akka is very modular and consists of several JARs containing different features.

- ``akka-actor`` – Classic Actors, Typed Actors, IO Actor etc.

- ``akka-agent`` – Agents, integrated with Scala STM

- ``akka-camel`` – Apache Camel integration

- ``akka-cluster`` – Cluster membership management, elastic routers.

- ``akka-osgi`` – utilities for using Akka in OSGi containers

- ``akka-osgi-aries`` – Aries blueprint for provisioning actor systems

- ``akka-remote`` – Remote Actors

- ``akka-slf4j`` – SLF4J Logger (event bus listener)

- ``akka-testkit`` – Toolkit for testing Actor systems

In addition to these stable modules there are several which are on their way
into the stable core but are still marked “experimental” at this point. This
does not mean that they do not function as intended, it primarily means that
their API has not yet solidified enough in order to be considered frozen. You
can help accelerating this process by giving feedback on these modules on our
mailing list.

- ``akka-contrib`` – an assortment of contributions which may or may not be
  moved into core modules, see :ref:`akka-contrib` for more details.

The filename of the actual JAR is for example ``@jarName@`` (and analog for
the other modules).

How to see the JARs dependencies of each Akka module is described in the
:ref:`dependencies` section.

Using a release distribution
----------------------------

Download the release you need from http://akka.io/downloads and unzip it.

Using a snapshot version
------------------------

The Akka nightly snapshots are published to http://repo.akka.io/snapshots/ and are
versioned with both ``SNAPSHOT`` and timestamps. You can choose a timestamped
version to work with and can decide when to update to a newer version.

.. warning::

  The use of Akka SNAPSHOTs, nightlies and milestone releases is discouraged unless you know what you are doing.

.. _build-tool:

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories.

Maven repositories
------------------

For Akka version 2.1-M2 and onwards:

`Maven Central <https://repo1.maven.org/maven2/>`_

For previous Akka versions:

`Akka Repo <http://repo.akka.io/releases/>`_

Using Akka with Maven
---------------------

The simplest way to get started with Akka and Maven is to check out the
`Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_
tutorial named `Akka Main in Java <http://www.lightbend.com/activator/template/akka-sample-main-java>`_.

Since Akka is published to Maven Central (for versions since 2.1-M2), it is
enough to add the Akka dependencies to the POM. For example, here is the
dependency for akka-actor:

.. code-block:: xml

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

For snapshot versions, the snapshot repository needs to be added as well:

.. code-block:: xml

    <repositories>
      <repository>
        <id>akka-snapshots</id>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
        <url>http://repo.akka.io/snapshots/</url>
      </repository>
    </repositories>

**Note**: for snapshot versions both ``SNAPSHOT`` and timestamped versions are published.


Using Akka with SBT
-------------------

The simplest way to get started with Akka and SBT is to use
`Lightbend Activator <http://www.lightbend.com/platform/getstarted>`_ with one of the SBT `templates <https://www.lightbend.com/activator/templates>`_.

Summary of the essential parts for using Akka with SBT:

SBT installation instructions on `http://www.scala-sbt.org/release/tutorial/Setup.html <http://www.scala-sbt.org/release/tutorial/Setup.html>`_

``build.sbt`` file:

.. parsed-literal::

    name := "My Project"

    version := "1.0"

    scalaVersion := "@scalaVersion@"

    libraryDependencies +=
      "com.typesafe.akka" %% "akka-actor" % "@version@" @crossString@

**Note**: the libraryDependencies setting above is specific to SBT v0.12.x and higher.  If you are using an older version of SBT, the libraryDependencies should look like this:

.. parsed-literal::

    libraryDependencies +=
      "com.typesafe.akka" % "akka-actor_@binVersion@" % "@version@"

For snapshot versions, the snapshot repository needs to be added as well:

.. parsed-literal::

    resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"


Using Akka with Gradle
----------------------

Requires at least `Gradle <https://gradle.org>`_ 1.4
Uses the `Scala plugin <http://www.gradle.org/docs/current/userguide/scala_plugin.html>`_

.. parsed-literal::

    apply plugin: 'scala'

    repositories {
      mavenCentral()
    }

    dependencies {
      compile 'org.scala-lang:scala-library:@scalaVersion@'
    }

    tasks.withType(ScalaCompile) {
      scalaCompileOptions.useAnt = false
    }

    dependencies {
      compile group: 'com.typesafe.akka', name: 'akka-actor_@binVersion@', version: '@version@'
      compile group: 'org.scala-lang', name: 'scala-library', version: '@scalaVersion@'
    }

For snapshot versions, the snapshot repository needs to be added as well:

.. parsed-literal::

    repositories {
      mavenCentral()
      maven {
        url "http://repo.akka.io/snapshots/"
      }
    }


Using Akka with Eclipse
-----------------------

Setup SBT project and then use `sbteclipse <https://github.com/typesafehub/sbteclipse>`_ to generate a Eclipse project.

Using Akka with IntelliJ IDEA
-----------------------------

Setup SBT project and then use `sbt-idea <https://github.com/mpeltonen/sbt-idea>`_ to generate a IntelliJ IDEA project.

Using Akka with NetBeans
------------------------

Setup SBT project and then use `nbsbt <https://github.com/dcaoyuan/nbsbt>`_ to generate a NetBeans project.

You should also use `nbscala <https://github.com/dcaoyuan/nbscala>`_ for general scala support in the IDE.

Do not use -optimize Scala compiler flag
----------------------------------------

.. warning::

  Akka has not been compiled or tested with -optimize Scala compiler flag.
  Strange behavior has been reported by users that have tried it.


Build from sources
------------------

Akka uses Git and is hosted at `Github <https://github.com>`_.

* Akka: clone the Akka repository from `<https://github.com/akka/akka>`_

Continue reading the page on :ref:`building-akka`

Need help?
----------

If you have questions you can get help on the `Akka Mailing List <https://groups.google.com/group/akka-user>`_.

You can also ask for `commercial support <https://www.lightbend.com>`_.

Thanks for being a part of the Akka community.

