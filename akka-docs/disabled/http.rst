.. _http-module:

HTTP
====

.. sidebar:: Contents

   .. contents:: :local:

Module stability: **SOLID**

When deploying in a servlet container:
--------------------------------------------

If you deploy Akka in a JEE container, don't forget to create an Akka initialization and cleanup hook:

.. code-block:: scala

  package com.my //<--- your own package
  import akka.util.AkkaLoader
  import akka.cluster.BootableRemoteActorService
  import akka.actor.BootableActorLoaderService
  import javax.servlet.{ServletContextListener, ServletContextEvent}

   /**
    * This class can be added to web.xml mappings as a listener to start and postStop Akka.
    *<web-app>
    * ...
    *  <listener>
    *    <listener-class>com.my.Initializer</listener-class>
    *  </listener>
    * ...
    *</web-app>
    */
  class Initializer extends ServletContextListener {
     lazy val loader = new AkkaLoader
     def contextDestroyed(e: ServletContextEvent): Unit = loader.shutdown
     def contextInitialized(e: ServletContextEvent): Unit =
       loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService) //<--- Important
  //     loader.boot(true, new BootableActorLoaderService {}) // If you don't need akka-remote
   }

For Java users, it's currently only possible to use BootableActorLoaderService, but you'll need to use: akka.actor.DefaultBootableActorLoaderService


Then you just declare it in your web.xml:

.. code-block:: xml

  <web-app>
  ...
    <listener>
      <listener-class>your.package.Initializer</listener-class>
    </listener>
  ...
  </web-app>

Adapting your own Akka Initializer for the Servlet Container
------------------------------------------------------------

If you want to use akka-camel or any other modules that have their own "Bootable"'s you'll need to write your own Initializer, which is _ultra_ simple, see below for an example on how to include Akka-camel.

.. code-block:: scala

  package com.my //<--- your own package
  import akka.cluster.BootableRemoteActorService
  import akka.actor.BootableActorLoaderService
  import akka.camel.CamelService
  import javax.servlet.{ServletContextListener, ServletContextEvent}

   /**
    * This class can be added to web.xml mappings as a listener to start and postStop Akka.
    *<web-app>
    * ...
    *  <listener>
    *    <listener-class>com.my.Initializer</listener-class>
    *  </listener>
    * ...
    *</web-app>
    */
  class Initializer extends ServletContextListener {
     lazy val loader = new AkkaLoader
     def contextDestroyed(e: ServletContextEvent): Unit = loader.shutdown
     def contextInitialized(e: ServletContextEvent): Unit =
       loader.boot(true, new BootableActorLoaderService with BootableRemoteActorService with CamelService) //<--- Important
   }

Using Akka with the Pinky REST/MVC framework
--------------------------------------------

Pinky has a slick Akka integration. Read more `here <http://wiki.github.com/pk11/pinky/release-13>`_

jetty-run in SBT
----------------

If you want to use jetty-run in SBT you need to exclude the version of Jetty that is bundled in akka-http:

.. code-block:: scala

  override def ivyXML =
    <dependencies>
      <dependency org="com.typesafe.akka" name="akka-http" rev="AKKA_VERSION_GOES_HERE">
        <exclude module="jetty"/>
      </dependency>
    </dependencies>


