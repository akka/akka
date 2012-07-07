Akka in OSGi
============

Configuring the OSGi Framework
------------------------------

To use Akka in an OSGi environment, the ``org.osgi.framework.bootdelegation``
property must be set to always delegate the ``sun.misc`` package to the boot classloader
instead of resolving it through the normal OSGi class space.


Activator
---------

To bootstrap Akka inside an OSGi environment, you can use the akka.osgi.AkkaSystemActivator class
to conveniently set up the ActorSystem.

.. includecode:: code/osgi/Activator.scala#Activator


Blueprint
---------

For the Apache Aries Blueprint implementation, there's also a namespace handler available.  The namespace URI
is http://akka.io/xmlns/blueprint/v1.0.0 and it can be used to set up an ActorSystem.

.. includecode:: code/osgi/blueprint.xml
