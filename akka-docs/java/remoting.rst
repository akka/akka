.. _remoting-java:

#####################
 Remoting (Java)
#####################

For an introduction of remoting capabilities of Akka please see :ref:`remoting`.

Preparing your ActorSystem for Remoting
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka remoting is a separate jar file. Make sure that you have the following dependency in your project::

  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-remote</artifactId>
    <version>2.0.2-RC1</version>
  </dependency>

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

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
There are lots of more properties that are related to remoting in Akka. We refer to the following
reference file for more information:

.. literalinclude:: ../../akka-remote/src/main/resources/reference.conf
   :language: none

Looking up Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^

``actorFor(path)`` will obtain an ``ActorRef`` to an Actor on a remote node::

  ActorRef actor = context.actorFor("akka://app@10.0.0.1:2552/user/serviceA/retrieval");

As you can see from the example above the following pattern is used to find an ``ActorRef`` on a remote node::

    akka://<actorsystemname>@<hostname>:<port>/<actor path>

For more details on how actor addresses and paths are formed and used, please refer to :ref:`addressing`.

Creating Actors Remotely
^^^^^^^^^^^^^^^^^^^^^^^^

The configuration below instructs the system to deploy the actor "retrieval” on the specific host "app@10.0.0.1".
The "app" in this case refers to the name of the ``ActorSystem`` (only showing deployment section)::

  akka {
    actor {
      deployment {
        /serviceA/retrieval {
          remote = "akka://app@10.0.0.1:2552"
        }
      }
    }
  }

Logical path lookup is supported on the node you are on, i.e. to use the
actor created above you would do the following:

.. includecode:: code/akka/docs/remoting/RemoteActorExample.java#localNodeActor

This will obtain an ``ActorRef`` on a remote node:

.. includecode:: code/akka/docs/remoting/RemoteActorExample.java#remoteNodeActor

As you can see from the example above the following pattern is used to find an ``ActorRef`` on a remote node::

    akka://<actorsystemname>@<hostname>:<port>/<actor path>

Programmatic Remote Deployment
------------------------------

To allow dynamically deployed systems, it is also possible to include
deployment configuration in the :class:`Props` which are used to create an
actor: this information is the equivalent of a deployment section from the
configuration file, and if both are given, the external configuration takes
precedence.

With these imports:

.. includecode:: code/akka/docs/remoting/RemoteDeploymentDocTestBase.java#import

and a remote address like this:

.. includecode:: code/akka/docs/remoting/RemoteDeploymentDocTestBase.java#make-address

you can advise the system to create a child on that remote node like so:

.. includecode:: code/akka/docs/remoting/RemoteDeploymentDocTestBase.java#deploy

Serialization
^^^^^^^^^^^^^

When using remoting for actors you must ensure that the ``props`` and ``messages`` used for
those actors are serializable. Failing to do so will cause the system to behave in an unintended way.

For more information please see :ref:`serialization-java`

Routers with Remote Destinations
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

It is absolutely feasible to combine remoting with :ref:`routing-java`.
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

.. _remote-lookup-sample-java:

Remote Lookup
-------------

In order to look up a remote actor, that one must be created first. For this
purpose, we configure an actor system to listen on port 2552 (this is a snippet
from ``application.conf``):

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/application.conf
   :include: calculator

Then the actor must be created. For all code which follows, assume these imports:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JLookupApplication.java
   :include: imports

The actor doing the work will be this one:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JSimpleCalculatorActor.java
   :include: actor

and we start it within an actor system using the above configuration

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JCalculatorApplication.java
   :include: setup

With the service actor up and running, we may look it up from another actor
system, which will be configured to use port 2553 (this is a snippet from
``application.conf``).

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/application.conf
   :include: remotelookup

The actor which will query the calculator is a quite simple one for demonstration purposes

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JLookupActor.java
   :include: actor

and it is created from an actor system using the aforementioned client’s config.

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JLookupApplication.java
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

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JLookupApplication.java
   :include: imports

The server actor can multiply or divide numbers:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JAdvancedCalculatorActor.java
   :include: actor

The client actor looks like in the previous example

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JCreationActor.java
   :include: actor

but the setup uses only ``actorOf``:

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/java/sample/remote/calculator/java/JCreationApplication.java
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

To intercept when an outbound connection is disconnected, you listen to ``RemoteClientDisconnected`` which
holds the transport used (RemoteTransport) and the outbound address that was disconnected (Address).

To intercept when an outbound connection is connected, you listen to ``RemoteClientConnected`` which
holds the transport used (RemoteTransport) and the outbound address that was connected to (Address).

To intercept when an outbound client is started you listen to ``RemoteClientStarted``
which holds the transport used (RemoteTransport) and the outbound address that it is connected to (Address).

To intercept when an outbound client is shut down you listen to ``RemoteClientShutdown``
which holds the transport used (RemoteTransport) and the outbound address that it was connected to (Address).

To intercept when an outbound message cannot be sent, you listen to ``RemoteClientWriteFailed`` which holds
the payload that was not written (AnyRef), the cause of the failed send (Throwable),
the transport used (RemoteTransport) and the outbound address that was the destination (Address).

For general outbound-related errors, that do not classify as any of the others, you can listen to ``RemoteClientError``,
which holds the cause (Throwable), the transport used (RemoteTransport) and the outbound address (Address).

To intercept when an inbound server is started (typically only once) you listen to ``RemoteServerStarted``
which holds the transport that it will use (RemoteTransport).

To intercept when an inbound server is shut down (typically only once) you listen to ``RemoteServerShutdown``
which holds the transport that it used (RemoteTransport).

To intercept when an inbound connection has been established you listen to ``RemoteServerClientConnected``
which holds the transport used (RemoteTransport) and optionally the address that connected (Option<Address>).

To intercept when an inbound connection has been disconnected you listen to ``RemoteServerClientDisconnected``
which holds the transport used (RemoteTransport) and optionally the address that disconnected (Option<Address>).

To intercept when an inbound remote client has been closed you listen to ``RemoteServerClientClosed``
which holds the transport used (RemoteTransport) and optionally the address of the remote client that was closed (Option<Address>).

