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



