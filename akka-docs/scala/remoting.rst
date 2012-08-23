.. _remoting-scala:

#################
 Remoting (Scala)
#################


For an introduction of remoting capabilities of Akka please see :ref:`remoting`.

Preparing your ActorSystem for Remoting
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka remoting is a separate jar file. Make sure that you have the following dependency in your project::

  "com.typesafe.akka" % "akka-remote" % "2.1-SNAPSHOT"

To enable remote capabilities in your Akka project you should, at a minimum, add the following changes
to your ``application.conf`` file::

  akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
    }
    remote {
      transport = "akka.remote.netty.NettyRemoteTransport"
      netty {
        hostname = "127.0.0.1"
        port = 2552
      }
   }
  }

As you can see in the example above there are four things you need to add to get started:

* Change provider from ``akka.actor.LocalActorRefProvider`` to ``akka.remote.RemoteActorRefProvider``
* Add host name - the machine you want to run the actor system on; this host
  name is exactly what is passed to remote systems in order to identify this
  system and consequently used for connecting back to this system if need be,
  hence set it to a reachable IP address or resolvable name in case you want to
  communicate across the network.
* Add port number - the port the actor system should listen on, set to 0 to have it chosen automatically

.. note::
  The port number needs to be unique for each actor system on the same machine even if the actor
  systems have different names. This is because each actor system has its own network subsystem
  listening for connections and handling messages as not to interfere with other actor systems.

.. _remoting-scala-configuration:

Remote Configuration
^^^^^^^^^^^^^^^^^^^^

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
There are lots of more properties that are related to remoting in Akka. We refer to the following
reference file for more information:

.. literalinclude:: ../../akka-remote/src/main/resources/reference.conf
   :language: none

Types of Remote Interaction
^^^^^^^^^^^^^^^^^^^^^^^^^^^

Akka has two ways of using remoting:

* Lookup    : used to look up an actor on a remote node with ``actorFor(path)``
* Creation  : used to create an actor on a remote node with ``actorOf(Props(...), actorName)``

In the next sections the two alternatives are described in detail.

Looking up Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^

``actorFor(path)`` will obtain an ``ActorRef`` to an Actor on a remote node, e.g.::

  val actor = context.actorFor("akka://actorSystemName@10.0.0.1:2552/user/actorName")

As you can see from the example above the following pattern is used to find an ``ActorRef`` on a remote node::

  akka://<actor system>@<hostname>:<port>/<actor path>

Once you obtained a reference to the actor you can interact with it they same way you would with a local actor, e.g.::

  actor ! "Pretty awesome feature"

.. note::

  For more details on how actor addresses and paths are formed and used, please refer to :ref:`addressing`.

Creating Actors Remotely
^^^^^^^^^^^^^^^^^^^^^^^^

If you want to use the creation functionality in Akka remoting you have to further amend the
``application.conf`` file in the following way (only showing deployment section)::

  akka {
    actor {
      deployment {
        /sampleActor {
          remote = "akka://sampleActorSystem@127.0.0.1:2553"
        }
      }
    }
  }

The configuration above instructs Akka to react when an actor with path ``/sampleActor`` is created, i.e.
using ``system.actorOf(Props(...)`, sampleActor)``. This specific actor will not be directly instantiated,
but instead the remote daemon of the remote system will be asked to create the actor,
which in this sample corresponds to ``sampleActorSystem@127.0.0.1:2553``.

Once you have configured the properties above you would do the following in code::

  class SampleActor extends Actor { def receive = { case _ => println("Got something") } }

  val actor = context.actorOf(Props[SampleActor], "sampleActor")
  actor ! "Pretty slick"

``SampleActor`` has to be available to the runtimes using it, i.e. the classloader of the
actor systems has to have a JAR containing the class.

.. note::

  In order to ensure serializability of ``Props`` when passing constructor
  arguments to the actor being created, do not make the factory an inner class:
  this will inherently capture a reference to its enclosing object, which in
  most cases is not serializable. It is best to create a factory method in the
  companion object of the actor’s class.

Programmatic Remote Deployment
------------------------------

To allow dynamically deployed systems, it is also possible to include
deployment configuration in the :class:`Props` which are used to create an
actor: this information is the equivalent of a deployment section from the
configuration file, and if both are given, the external configuration takes
precedence.

With these imports:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#import

and a remote address like this:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#make-address

you can advise the system to create a child on that remote node like so:

.. includecode:: code/docs/remoting/RemoteDeploymentDocSpec.scala#deploy

Serialization
^^^^^^^^^^^^^

When using remoting for actors you must ensure that the ``props`` and ``messages`` used for
those actors are serializable. Failing to do so will cause the system to behave in an unintended way.

For more information please see :ref:`serialization-scala`

Routers with Remote Destinations
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

It is absolutely feasible to combine remoting with :ref:`routing-scala`.
This is also done via configuration::

  akka {
    actor {
      deployment {
        /serviceA/aggregation {
          router = "round-robin"
          nr-of-instances = 10
          target {
            nodes = ["akka://app@10.0.0.2:2552", "akka://app@10.0.0.3:2552"]
          }
        }
      }
    }
  }

This configuration setting will clone the actor “aggregation” 10 times and deploy it evenly distributed across
the two given target nodes.

Description of the Remoting Sample
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

There is a more extensive remote example that comes with the Akka distribution.
Please have a look here for more information: `Remote Sample
<https://github.com/akka/akka/tree/master/akka-samples/akka-sample-remote>`_
This sample demonstrates both, remote deployment and look-up of remote actors.
First, let us have a look at the common setup for both scenarios (this is
``common.conf``):

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/common.conf

This enables the remoting by installing the :class:`RemoteActorRefProvider` and
chooses the default remote transport. All other options will be set
specifically for each show case.

.. note::

  Be sure to replace the default IP 127.0.0.1 with the real address the system
  is reachable by if you deploy onto multiple machines!

.. _remote-lookup-sample-scala:

Remote Lookup
-------------

In order to look up a remote actor, that one must be created first. For this
purpose, we configure an actor system to listen on port 2552 (this is a snippet
from ``application.conf``):

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/application.conf
   :include: calculator

Then the actor must be created. For all code which follows, assume these imports:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/LookupApplication.scala
   :include: imports

The actor doing the work will be this one:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/CalculatorApplication.scala
   :include: actor

and we start it within an actor system using the above configuration

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/CalculatorApplication.scala
   :include: setup

With the service actor up and running, we may look it up from another actor
system, which will be configured to use port 2553 (this is a snippet from
``application.conf``).

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/application.conf
   :include: remotelookup

The actor which will query the calculator is a quite simple one for demonstration purposes

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/LookupApplication.scala
   :include: actor

and it is created from an actor system using the aforementioned client’s config.

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/LookupApplication.scala
   :include: setup

Requests which come in via ``doSomething`` will be sent to the client actor
along with the reference which was looked up earlier. Observe how the actor
system name using in ``actorFor`` matches the remote system’s name, as do IP
and port number. Top-level actors are always created below the ``"/user"``
guardian, which supervises them.

Remote Deployment
-----------------

Creating remote actors instead of looking them up is not visible in the source
code, only in the configuration file. This section is used in this scenario
(this is a snippet from ``application.conf``):

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/application.conf
   :include: remotecreation

For all code which follows, assume these imports:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/LookupApplication.scala
   :include: imports

The client actor looks like in the previous example

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/CreationApplication.scala
   :include: actor

but the setup uses only ``actorOf``:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/scala/sample/remote/calculator/CreationApplication.scala
   :include: setup

Observe how the name of the server actor matches the deployment given in the
configuration file, which will transparently delegate the actor creation to the
remote node.

Remote Events
-------------

It is possible to listen to events that occur in Akka Remote, and to subscribe/unsubscribe to there events,
you simply register as listener to the below described types in on the ``ActorSystem.eventStream``.

.. note::
    To subscribe to any outbound-related events, subscribe to ``RemoteClientLifeCycleEvent``
    To subscribe to any inbound-related events, subscribe to ``RemoteServerLifeCycleEvent``
    To subscribe to any remote events, subscribe to ``RemoteLifeCycleEvent``

By default an event listener is registered which logs all of the events
described below. This default was chosen to help setting up a system, but it is
quite common to switch this logging off once that phase of the project is
finished.

.. note::
  In order to switch off the logging, set
  ``akka.remote.log-remote-lifecycle-events = off`` in your
  ``application.conf``.

To intercept when an outbound connection is disconnected, you listen to ``RemoteClientDisconnected`` which
holds the transport used (RemoteTransport) and the outbound address that was disconnected (Address).

To intercept when an outbound connection is connected, you listen to ``RemoteClientConnected`` which
holds the transport used (RemoteTransport) and the outbound address that was connected to (Address).

To intercept when an outbound client is started you listen to ``RemoteClientStarted``
which holds the transport used (RemoteTransport) and the outbound address that it is connected to (Address).

To intercept when an outbound client is shut down you listen to ``RemoteClientShutdown``
which holds the transport used (RemoteTransport) and the outbound address that it was connected to (Address).

For general outbound-related errors, that do not classify as any of the others, you can listen to ``RemoteClientError``,
which holds the cause (Throwable), the transport used (RemoteTransport) and the outbound address (Address).

To intercept when an inbound server is started (typically only once) you listen to ``RemoteServerStarted``
which holds the transport that it will use (RemoteTransport).

To intercept when an inbound server is shut down (typically only once) you listen to ``RemoteServerShutdown``
which holds the transport that it used (RemoteTransport).

To intercept when an inbound connection has been established you listen to ``RemoteServerClientConnected``
which holds the transport used (RemoteTransport) and optionally the address that connected (Option[Address]).

To intercept when an inbound connection has been disconnected you listen to ``RemoteServerClientDisconnected``
which holds the transport used (RemoteTransport) and optionally the address that disconnected (Option[Address]).

To intercept when an inbound remote client has been closed you listen to ``RemoteServerClientClosed``
which holds the transport used (RemoteTransport) and optionally the address of the remote client that was closed (Option[Address]).

Remote Security
^^^^^^^^^^^^^^^

Akka provides a couple of ways to enhance security between remote nodes (client/server):

* Untrusted Mode
* Security Cookie Handshake

Untrusted Mode
--------------

You can enable untrusted mode for preventing system messages to be send by clients, e.g. messages like.
This will prevent the client to send these messages to the server:

* ``Create``
* ``Recreate``
* ``Suspend``
* ``Resume``
* ``Terminate``
* ``Supervise``
* ``ChildTerminated``
* ``Link``
* ``Unlink``

Here is how to turn it on in the config::

    akka.remote.untrusted-mode = on

Secure Cookie Handshake
-----------------------

Akka remoting also allows you to specify a secure cookie that will be exchanged and ensured to be identical
in the connection handshake between the client and the server. If they are not identical then the client
will be refused to connect to the server.

The secure cookie can be any kind of string. But the recommended approach is to generate a cryptographically
secure cookie using this script ``$AKKA_HOME/scripts/generate_config_with_secure_cookie.sh`` or from code
using the ``akka.util.Crypt.generateSecureCookie()`` utility method.

You have to ensure that both the connecting client and the server have the same secure cookie as well
as the ``require-cookie`` option turned on.

Here is an example config::

    akka.remote.netty {
      secure-cookie = "090A030E0F0A05010900000A0C0E0C0B03050D05"
      require-cookie = on
    }

SSL
---

SSL can be used for the remote transport by activating the ``akka.remote.netty.ssl``
configuration section. See description of the settings in the :ref:`remoting-scala-configuration`.
  
The SSL support is implemented with Java Secure Socket Extension, please consult the offical 
`Java Secure Socket Extension documentation <http://docs.oracle.com/javase/7/docs/technotes/guides/security/jsse/JSSERefGuide.html>`_
and related resources for troubleshooting.
  
.. note::

  When using SHA1PRNG on Linux it's recommended specify ``-Djava.security.egd=file:/dev/./urandom`` as argument 
  to the JVM to prevent blocking. It is NOT as secure because it reuses the seed.
  Use '/dev/./urandom', not '/dev/urandom' as that doesn't work according to 
  `Bug ID: 6202721 <http://bugs.sun.com/view_bug.do?bug_id=6202721>`_.
  
