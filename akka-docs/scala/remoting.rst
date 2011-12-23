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

* Change provider from ``LocalActorRefProvider`` to ``RemoteActorRefProvider``
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

* Lookup    : used to look up an actor on a remote node
* Creation  : used to create an actor on a remote node

In the next sections these ways are described in detail.

Looking up Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^

``actorFor(path)`` will obtain an ``ActorRef`` to an Actor on a remote node, e.g.::

  val actor = context.actorFor("akka://app@10.0.0.1:2552/user/serviceA/retrieval")

As you can see from the example above the following pattern is used to find an ``ActorRef`` on a remote node::

  akka://<actorsystemname>@<hostname>:<port>/<actor path>

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

The configuration above instructs Akka to react specially once when an actor at path /actorName is created, i.e.
using system.actorOf(Props(...), "sampleActor"). This specific actor will not only be instantiated,
but instead the remote daemon of the remote system will be asked to create the actor instead,
which is at sampleActorSystem@127.0.0.1:2553 in this sample.

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
