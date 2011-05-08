Getting Started
===============

There are several ways to download Akka. You can download the full distribution with microkernel, which includes all modules. You can download just the core distribution. Or you can use a build tool like Maven or SBT to download dependencies from the Akka Maven repository.

A list of each of the Akka module JARs dependencies can be found `here <http://doc.akka.io/building-akka#Dependencies>`_.

Using a release distribution
----------------------------

Akka is split up into two different parts:

* Akka - The core modules. Reflects all the sections under 'Scala API' and 'Java API' in the navigation bar.
* Akka Modules - The microkernel and add-on modules. Reflects all the sections under 'Add-on modules' in the navigation bar.

Download the release you need (Akka core or Akka Modules) from `<http://akka.io/downloads>`_ and unzip it.

Microkernel
^^^^^^^^^^^

The Akka Modules distribution includes the microkernel. To run the microkernel:

* Set the AKKA_HOME environment variable to the root of the Akka distribution.
* Run ``java -jar akka-modules-1.0.jar``. This will boot up the microkernel and deploy all samples applications from './deploy' dir.

For example (bash shell):

::

  cd akka-modules-1.0
  export AKKA_HOME=`pwd`
  java -jar akka-modules-1.0.jar

Now you can continue with reading the `tutorial <tutorial-chat-server>`_ and try to build the tutorial sample project step by step. This can be a good starting point before diving into the reference documentation which can be navigated in the left sidebar.

Using a build tool
------------------

Akka can be used with build tools that support Maven repositories. The Akka Maven repository can be found at `<http://akka.io/repository>`_.

Using Akka with Maven
^^^^^^^^^^^^^^^^^^^^^

If you want to use Akka with Maven then you need to add this repository to your ``pom.xml``:

.. code-block:: xml

  <repository>
    <id>Akka</id>
    <name>Akka Maven2 Repository</name>
    <url>http://akka.io/repository/ </url>
  </repository>

Then you can add the Akka dependencies. For example, here is the dependency for Akka Actor 1.0:

.. code-block:: xml

  <dependency>
    <groupId>se.scalablesolutions.akka</groupId>
    <artifactId>akka-actor</artifactId>
    <version>1.0</version>
  </dependency>

Using Akka with SBT
^^^^^^^^^^^^^^^^^^^

Akka has an SBT plugin which makes it very easy to get started with Akka and SBT.

The Scala version in your SBT project needs to match the version that Akka is built against. For 1.0 this is 2.8.1.

To use the plugin, first add a plugin definition to your SBT project by creating project/plugins/Plugins.scala with:

.. code-block:: scala

  import sbt._

  class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
    val akkaRepo = "Akka Repo" at "http://akka.io/repository"
    val akkaPlugin = "se.scalablesolutions.akka" % "akka-sbt-plugin" % "1.0"
  }

*Note: the plugin version matches the Akka version provided. The current release is 1.0.*

Then mix the AkkaProject trait into your project definition. For example:

.. code-block:: scala

  class MyProject(info: ProjectInfo) extends DefaultProject(info) with AkkaProject

*Note: This adds akka-actor as a dependency by default.*

If you also want to include other Akka modules there is a convenience method: ``akkaModule``. For example, you can add extra Akka modules by adding any of the following lines to your project class:

.. code-block:: scala

  val akkaStm = akkaModule("stm")
  val akkaTypedActor = akkaModule("typed-actor")
  val akkaRemote = akkaModule("remote")
  val akkaHttp = akkaModule("http")
  val akkaAmqp = akkaModule("amqp")
  val akkaCamel = akkaModule("camel")
  val akkaCamelTyped = akkaModule("camel-typed")
  val akkaSpring = akkaModule("spring")
  val akkaJta = akkaModule("jta")
  val akkaCassandra = akkaModule("persistence-cassandra")
  val akkaMongo = akkaModule("persistence-mongo")
  val akkaRedis = akkaModule("persistence-redis")

Build from sources
------------------

Akka uses Git and is hosted at `Github <http://github.com>`_.

* Akka: clone the Akka repository from `<http://github.com/jboner/akka>`_
* Akka Modules: clone the Akka Modules repository from `<http://github.com/jboner/akka-modules>`_

Continue reading the page on `how to build and run Akka <building-akka>`_

Need help?
----------

If you have questions you can get help on the `Akka Mailing List <http://groups.google.com/group/akka-user>`_.

You can also ask for `commercial support <http://scalablesolutions.se>`_.

Thanks for being a part of the Akka community.
