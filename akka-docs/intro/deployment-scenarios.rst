
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
its dependency jars into ``WEB-INF/lib``. You also need to put the ``akka.conf``
config file in the ``$AKKA_HOME/config`` directory.  Now you can create your
Actors as regular services referenced from your Web application. You should also
be able to use the Remoting service, e.g. be able to make certain Actors remote
on other hosts. Please note that remoting service does not speak HTTP over port
80, but a custom protocol over the port is specified in ``akka.conf``.


Using Akka as a stand alone microkernel
---------------------------------------

Akka can also be run as a stand-alone microkernel. It implements a full
enterprise stack. See the :ref:`add-on-modules` for more information.

Using the Akka sbt plugin to package your application
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka sbt plugin can create a full Akka microkernel deployment for your sbt
project.

To use the plugin, first add a plugin definition to your SBT project by creating
``project/plugins/Plugins.scala`` with::

   import sbt._

   class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
     val akkaRepo = "Akka Repo" at "http://akka.io/repository"
     val akkaPlugin = "se.scalablesolutions.akka" % "akka-sbt-plugin" % "1.3-RC7"
   }

Then mix the ``AkkaKernelProject`` trait into your project definition. For
example::

  class MyProject(info: ProjectInfo) extends DefaultProject(info) with AkkaKernelProject

This will automatically add all the Akka dependencies needed for a microkernel
deployment (download them with ``sbt update``).

Place your config files in ``src/main/config``.

To build a microkernel deployment use the ``dist`` task::

   sbt dist
