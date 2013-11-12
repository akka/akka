Akka in OSGi
============

In an OSGi environment the ``akka-osgi`` bundle replaces ``akka-actor`` artifact. It includes all classes from ``akka-actor`` and merged ``reference.conf`` files from all akka modules. The dependency is::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-osgi_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Configuring the OSGi Framework
------------------------------

To use Akka in an OSGi environment, the ``org.osgi.framework.bootdelegation``
property must be set to always delegate the ``sun.misc`` package to the boot classloader
instead of resolving it through the normal OSGi class space.

Activator
---------

To bootstrap Akka inside an OSGi environment, you can use the ``akka.osgi.AkkaSystemActivator`` class
to conveniently set up the ActorSystem.

.. includecode:: code/osgi/Activator.scala#Activator

The ``AkkaSystemActivator`` class is included in the ``akka-osgi`` artifact.

Blueprint
---------

For the Apache Aries Blueprint implementation, there's also a namespace handler available.  The namespace URI
is http://akka.io/xmlns/blueprint/v1.0.0 and it can be used to set up an ActorSystem.

.. includecode:: code/osgi/blueprint.xml

The blueprint is included in the ``akka-osgi-aries`` artifact::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-osgi-aries_@binVersion@</artifactId>
    <version>@version@</version>
  </dependency>

Sample
------

A complete sample project is provided in `akka-sample-osgi-dining-hakkers <@github@/akka-samples/akka-sample-osgi-dining-hakkers>`_.
 
