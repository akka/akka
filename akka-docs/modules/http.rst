.. _http-module:

HTTP
====

.. sidebar:: Contents

   .. contents:: :local:

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

