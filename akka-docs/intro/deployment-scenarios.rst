
.. _deployment-scenarios:

###################################
 Use-case and Deployment Scenarios
###################################

How can I use and deploy Akka?
==============================

Akka can be used in two different ways:

- As a library: used as a regular JAR on the classpath and/or in a web app, to
  be put into ``WEB-INF/lib``

- As a microkernel: stand-alone microkernel, embedding a servlet container along
  with many other services


Using Akka as library
---------------------

This is most likely what you want if you are building Web applications. There
are several ways you can use Akka in Library mode by adding more and more
modules to the stack.

Actors as services
^^^^^^^^^^^^^^^^^^

The simplest way you can use Akka is to use the actors as services in your Web
application. All that’s needed to do that is to put the Akka charts as well as
its dependency jars into ``WEB-INF/lib``. You also need to put the :ref:`configuration`
file in the ``$AKKA_HOME/config`` directory.  Now you can create your
Actors as regular services referenced from your Web application. You should also
be able to use the Remoting service, e.g. be able to make certain Actors remote
on other hosts. Please note that remoting service does not speak HTTP over port
80, but a custom protocol over the port is specified in :ref:`configuration`.


Using Akka as a stand alone microkernel
---------------------------------------

Akka can also be run as a stand-alone microkernel. It implements a full
enterprise stack. See the :ref:`microkernel` for more information.

Using the Akka sbt plugin to package your application
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka sbt plugin can create a full Akka microkernel deployment for your sbt
project.

To use the plugin, first add a plugin definition to your sbt project by creating
``project/plugins.sbt`` with::

   resolvers += Classpaths.typesafeResolver

   addSbtPlugin("com.typesafe.akka" % "akka-sbt-plugin" % "2.0-SNAPSHOT")

Then use the AkkaKernelPlugin settings. In a 'light' configuration (build.sbt)::

   seq(akka.sbt.AkkaKernelPlugin.distSettings: _*)

Or in a 'full' configuration (Build.scala). For example::

   import sbt._
   import sbt.Keys._
   import akka.sbt.AkkaKernelPlugin

   object SomeBuild extends Build {
     lazy val someProject = Project(
       id = "some-project",
       base = file("."),
       settings = Defaults.defaultSettings ++ AkkaKernelPlugin.distSettings ++ Seq(
         organization := "org.some",
         version := "0.1",
         scalaVersion := "2.9.1"
         resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
         libraryDependencies += "com.typesafe.akka" % "akka-kernel" % "2.0-SNAPSHOT"
       )
     )
   }

To build a microkernel deployment use the ``dist`` task::

   sbt dist
