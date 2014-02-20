Akka in OSGi
============

Configuring the OSGi Framework
------------------------------

To use Akka in an OSGi environment, the ``org.osgi.framework.bootdelegation``
property must be set to always delegate the ``sun.misc`` package to the boot classloader
instead of resolving it through the normal OSGi class space.

Activator
---------

To bootstrap Akka inside an OSGi environment, you can use the ``akka.osgi.ActorSystemActivator`` class
to conveniently set up the ActorSystem.

.. includecode:: code/docs/osgi/Activator.scala#Activator


The ``ActorSystemActivator`` creates the actor system with a class loader that finds resources
(``reference.conf`` files) and classes from the application bundle and all transitive dependencies.

The ``ActorSystemActivator`` class is included in the ``akka-osgi`` artifact::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-osgi_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>


Sample
------

A complete sample project is provided in `akka-sample-osgi-dining-hakkers <@github@/akka-samples/akka-sample-osgi-dining-hakkers>`_.
 
