.. _remoting-scala:

#################
 Remoting (Scala)
#################

For an introduction of remoting capabilities of Akka please see :ref:`remoting`.

Preparing your ActorSystem for Remoting
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Akka remoting is a separate jar file. Make sure that you have a dependency from your project to this jar::

  akka-remote.jar

In you SBT project you should add the following as a dependency::

  "com.typesafe.akka" % "akka-remote" % "2.0-SNAPSHOT"

To enable remote capabilities in your Akka project you should, at a minimum, add the following changes
to your ``application.conf`` file::

  akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
    }
    remote {
      transport = "akka.remote.netty.NettyRemoteSupport"
      server {
        hostname = "127.0.0.1"
        port = 2552
      }
   }
   cluster.nodename = "someUniqueNameInTheCluster1"
  }

As you can see in the example above there are four things you need to add to get started:

* Change provider from ``akka.actor.LocalActorRefProvider`` to ``akka.remote.RemoteActorRefProvider``
* Add host name - the machine you want to run the actor system on
* Add port number - the port the actor system should listen on
* Add cluster node name - must be a unique name in the cluster

The example above only illustrates the bare minimum of properties you have to add to enable remoting.
There are lots of more properties that are related to remoting in Akka. We refer to the following
reference file for more information:

* `reference.conf of akka-remote <https://github.com/jboner/akka/blob/master/akka-remote/src/main/resources/reference.conf#L39>`_

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

Once you a reference to the actor you can interact with it they same way you would with a local actor, e.g.::

  actor ! "Pretty awesome feature"

Creating Actors Remotely
^^^^^^^^^^^^^^^^^^^^^^^^

If you want to use the creation functionality in Akka remoting you have to further amend the
``application.conf`` file in the following way::

  akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
      deployment { /sampleActor {
        remote = "akka://sampleActorSystem@127.0.0.1:2553"
      }}
    }
    ...

The configuration above instructs Akka to react when an actor with path /sampleActor is created, i.e.
using ``system.actorOf(Props(...)`, sampleActor)``. This specific actor will not be directly instantiated,
but instead the remote daemon of the remote system will be asked to create the actor,
which in this sample corresponds to ``sampleActorSystem@127.0.0.1:2553``.

Once you have configured the properties above you would do the following in code::

  class SampleActor extends Actor { def receive = { case _ => println("Got something") } }

  val actor = context.actorOf(Props[SampleActor], "sampleActor")
  actor ! "Pretty slick"

``SampleActor`` has to be available to the runtimes using it, i.e. the classloader of the
actor systems has to have a JAR containing the class.

Remote Sample Code
^^^^^^^^^^^^^^^^^^

There is a more extensive remote example that comes with the Akka distribution.
Please have a look here for more information:
`Remote Sample <https://github.com/jboner/akka/tree/master/akka-samples/akka-sample-remote>`_

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
          router = “round-robin”
          nr-of-instances = 10
          routees {
            nodes = [“akka://app@10.0.0.2:2552”, “akka://app@10.0.0.3:2552”]
          }
        }
      }
    }
  }

This configuration setting will clone the actor “aggregation” 10 times and deploy it evenly distributed across
the two given target nodes.

Description of the Remoting Sample
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The sample application included with the Akka sources demonstrates both, remote
deployment and look-up of remote actors. First, let us have a look at the
common setup for both scenarios (this is ``common.conf``):

.. includecode:: ../../akka-samples/akka-sample-remote/src/main/resources/common.conf

This enables the remoting by installing the :class:`RemoteActorRefProvider` and
chooses the default remote transport. All other options will be set
specifically for each show case.

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



