Cluster Membership (Scala)
==========================

Module stability: **IN PROGRESS**

Akka supports a Cluster Membership through a `JGroups <http://www.jgroups.org/>`_ based implementation. JGroups is is a `P2P <http://en.wikipedia.org/wiki/Peer-to-peer>`_ clustering API

Configuration
-------------

The cluster is configured in 'akka.conf' by adding the Fully Qualified Name (FQN) of the actor class and serializer:

.. code-block:: ruby

  remote {
    cluster {
      service = on
      name = "default"                                  # The name of the cluster
      serializer = "akka.serialization.Serializer$Java" # FQN of the serializer class
    }
  }

How to join the cluster
-----------------------

The node joins the cluster when the 'RemoteNode' and/or 'RemoteServer' servers are started.

Cluster API
-----------

Interaction with the cluster is done through the 'akka.remote.Cluster' object.

To send a message to all actors of a specific type on other nodes in the cluster use the 'relayMessage' function:

.. code-block:: scala

  def relayMessage(to: Class[_ <: Actor], msg: AnyRef): Unit

Here is an example:

.. code-block:: scala

  Cluster.relayMessage(classOf[ATypeOfActor], message)

Traversing the remote nodes in the cluster to spawn remote actors:

Cluster.foreach:

.. code-block:: scala

  def foreach(f : (RemoteAddress) => Unit) : Unit

Here's an example:

.. code-block:: scala

  for(endpoint <- Cluster) spawnRemote[KungFuActor](endpoint.hostname,endpoint.port)

and:

.. code-block:: scala

  Cluster.foreach( endpoint => spawnRemote[KungFuActor](endpoint.hostname,endpoint.port) )

Cluster.lookup:

.. code-block:: scala

  def lookup[T](handleRemoteAddress : PartialFunction[RemoteAddress, T]) : Option[T]

Here is an example:

.. code-block:: scala

  val myRemoteActor: Option[SomeActorType] = Cluster.lookup({
    case RemoteAddress(hostname, port) => spawnRemote[SomeActorType](hostname, port)
  })

  myRemoteActor.foreach(remoteActor => ...)

Here is another example:

.. code-block:: scala
  Cluster.lookup({
    case remoteAddress @ RemoteAddress(_,_) => remoteAddress
  }) match {
    case Some(remoteAddress) => spawnAllRemoteActors(remoteAddress)
    case None =>                handleNoRemoteNodeFound
  }
