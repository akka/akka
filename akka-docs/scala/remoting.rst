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

First of all you have to change the actor provider from ``LocalActorRefProvider`` to ``RemoteActorRefProvider``::

  akka {
    actor {
     provider = "akka.remote.RemoteActorRefProvider"
    }
  }

After that you must also add the following settings::

  akka {
    remote {
      server {
        # The hostname or ip to bind the remoting to,
        # InetAddress.getLocalHost.getHostAddress is used if empty
        hostname = ""

        # The default remote server port clients should connect to.
        # Default is 2552 (AKKA)
        port = 2552
      }
    }
  }

These are the bare minimal settings that must exist in order to get started with remoting.
There are, of course, more properties that can be tweaked. We refer to the following
reference file for more information:

* `reference.conf of akka-remote <https://github.com/jboner/akka/blob/master/akka-remote/src/main/resources/reference.conf#L39>`_

Looking up Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^

``actorFor(path)`` will obtain an ``ActorRef`` to an Actor on a remote node::

  val actor = context.actorFor("akka://app@10.0.0.1:2552/user/serviceA/retrieval")

As you can see from the example above the following pattern is used to find an ``ActorRef`` on a remote node::

    akka://<actorsystemname>@<hostname>:<port>/<actor path>

Creating Actors Remotely
^^^^^^^^^^^^^^^^^^^^^^^^

The configuration below instructs the system to deploy the actor "retrieval” on the specific host "app@10.0.0.1".
The "app" in this case refers to the name of the ``ActorSystem``::

  akka {
    actor {
      deployment {
        /serviceA/retrieval {
          remote = “akka://app@10.0.0.1:2552”
        }
      }
    }
  }

Logical path lookup is supported on the node you are on, i.e. to use the
actor created above you would do the following::

  val actor = context.actorFor("/serviceA/retrieval")

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
