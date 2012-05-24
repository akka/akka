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
  using the microkernel


Using Akka as library
---------------------

This is most likely what you want if you are building Web applications. There
are several ways you can use Akka in Library mode by adding more and more
modules to the stack.


Using Akka as a stand alone microkernel
----------------------------------------

Akka can also be run as a stand-alone microkernel. For more information see
:ref:`microkernel-java` / :ref:`microkernel-scala`.
