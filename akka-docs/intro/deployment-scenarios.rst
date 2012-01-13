.. _deployment-scenarios:

###################################
 Use-case and Deployment Scenarios
###################################

How can I use and deploy Akka?
==============================

Akka can be used in different ways:

- As a library: used as a regular JAR on the classpath and/or in a web app, to
  be put into ``WEB-INF/lib``

- As a stand alone application by instantiating ActorSystem in a main class or
  using the :ref:`microkernel`


Using Akka as library
---------------------

This is most likely what you want if you are building Web applications. There
are several ways you can use Akka in Library mode by adding more and more
modules to the stack.

Actors as services
^^^^^^^^^^^^^^^^^^

The simplest way you can use Akka is to use the actors as services in your Web
application. All that’s needed to do that is to put the Akka jars as well as
its dependency jars into ``WEB-INF/lib``. You also need to put the :ref:`configuration`
file in the ``$AKKA_HOME/config`` directory.  Now you can create your
Actors as regular services referenced from your Web application. You should also
be able to use the Remoting service, e.g. be able to make certain Actors remote
on other hosts. Please note that remoting service does not speak HTTP over port
80, but a custom protocol over the port is specified in :ref:`configuration`.


Using Akka as a stand alone microkernel
----------------------------------------

Akka can also be run as a stand-alone microkernel. See :ref:`microkernel` for
more information.
