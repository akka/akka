Clustering
==========


Overview
--------

The clustering module provides services like group membership, clustering, and
failover of actors.

The clustering module is based on ZooKeeper.


Starting up the ZooKeeper ensemble
----------------------------------

Embedded ZooKeeper server
~~~~~~~~~~~~~~~~~~~~~~~~~

For testing purposes the simplest way is to start up a single embedded ZooKeeper
server. This can be done like this:

.. literalinclude:: examples/clustering.scala
   :language: scala
   :lines: 1-2,5,2-4,7

You can leave ``port`` and ``tickTime`` out which will then default to port 2181
and tick time 5000 ms.

ZooKeeper server ensemble
~~~~~~~~~~~~~~~~~~~~~~~~~

For production you should always run an ensemble of at least 3 servers. The
number should be quorum-based, e.g. 3, 5, 7 etc.

|more| Read more about this in the `ZooKeeper Installation and Admin Guide
<http://hadoop.apache.org/zookeeper/docs/r3.1.1/zookeeperAdmin.htm>`_.

In the future Cloudy Akka Provisioning module will automate this.


Creating, starting and stopping a cluster node
----------------------------------------------

Once we have one or more ZooKeeper servers running we can create and start up a
cluster node.

Cluster configuration
~~~~~~~~~~~~~~~~~~~~~

Cluster is configured in the ``akka.cloud.cluster`` section in the ``akka.conf``
configuration file. Here you specify the default addresses to the ZooKeeper
servers, timeouts, if compression should be on or off, and so on.

.. code-block:: conf

    akka {
      cloud {
        cluster {
           zookeeper-server-addresses = "wallace:2181,gromit:2181"
           remote-server-port = 2552
           max-time-to-wait-until-connected = 5
           session-timeout = 60
           connection-timeout = 30
           use-compression = on
         }
       }
    }

Creating a node
~~~~~~~~~~~~~~~

The first thing you need to do on each node is to create a new cluster
node. That is done by invoking ``newNode`` on the ``Cluster`` object. Here is
the signature with its default values::

    def newNode(
      nodeAddress: NodeAddress,
      zkServerAddresses: String = Cluster.zooKeeperServers,
      serializer: ZkSerializer  = Cluster.defaultSerializer,
      hostname: String          = NetworkUtil.getLocalhostName,
      remoteServerPort: Int     = Cluster.remoteServerPort): ClusterNode

The ``NodeAddress`` defines the address for a node and has the following
signature::

     final case class NodeAddress(
       clusterName: String,
       nodeName: String,
       hostname: String = Cluster.lookupLocalhostName,
       port: Int        = Cluster.remoteServerPort)

You have to specify a cluster name and node name while the hostname and port for
the remote server can be left out to use default values.

Here is a an example of creating a node in which we only specify the node address
and let the rest of the configuration options have their default values::

    import akka.cloud.cluster._

    val clusterNode = Cluster.newNode(NodeAddress("test-cluster", "node1"))

You can also use the ``apply`` method on the ``Cluster`` object to create a new
node in a more idiomatic Scala way::

    import akka.cloud.cluster._

    val clusterNode = Cluster(NodeAddress("test-cluster", "node1"))

The ``NodeAddress`` defines the name of the node and the name of cluster. This
allows you to have multiple clusters running in parallel in isolation,
not aware of each other.

The other parameters to know are:

  - ``zkServerAddresses`` -- a list of the ZooKeeper servers to connect to,
    default is "localhost:2181"

  - ``serializer`` -- the serializer to use when serializing configuration data
    into the cluster. Default is ``Cluster.defaultSerializer`` which is using Java
    serialization

  - ``hostname`` -- the hostname to use for the node

  - ``remoteServerPort`` -- the remote server port, for the internal remote server

Starting a node
~~~~~~~~~~~~~~~

Creating a node does not make it join the cluster. In order to do that you need
to invoke the ``start`` method::

    val clusterNode = Cluster.newNode(NodeAddress("test-cluster", "node1"))
    clusterNode.start

Or if you prefer to do it in one line of code::

    val clusterNode = Cluster.newNode(NodeAddress("test-cluster", "node1")).start

Stopping a node
~~~~~~~~~~~~~~~

To stop a node invoke ``stop``::

    clusterNode.stop

Querying which nodes are part of the cluster
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

You can query the cluster for which nodes have joined the cluster. This is done using the ``membershipNodes``method::

    val allNodesInCluster = clusterNode.membershipNodes

You can also query the 'Cluster' object for which nodes are member of the a specific cluster::

    val nodes = Cluster nodesInCluster "test-cluster"

Resetting the Cluster
~~~~~~~~~~~~~~~~~~~~~

You can reset the whole cluster using the ``reset`` method on the ``Cluster`` object::

    Cluster.reset

This shuts down all nodes and removes them from the cluster, it also removes all clustered actors and configuration data from the registry. Use this method with care.

You can reset all the nodes in a specific cluster using the ``resetNodesInCluster`` method on the ``Cluster`` object::

    Cluster resetNodesInCluster "test-cluster"

Cluster event subscription
--------------------------

The cluster module supports subscribing to events happening in the cluster. For
example, this can be very useful for knowing when a new nodes come and go,
allowing you to dynamically resize the cluster. Here is an example::

    clusterNode.register(new ChangeListener {
      override def nodeConnected(node: String, thisNode: ClusterNode) {
        // ...
      }

      override def nodeDisconnected(node: String, thisNode: ClusterNode) {
        // ...
      }
    })

As parameters into these callbacks the cluster passes the name of the node that
joined or left the cluster as well as the local node itself.

Here is the full trait with all the callbacks you can implement::

    trait ChangeListener {
      def nodeConnected(node: String, client: ClusterNode) = {}
      def nodeDisconnected(node: String, client: ClusterNode) = {}
      def newLeader(name: String, client: ClusterNode) = {}
      def thisNodeNewSession(client: ClusterNode) = {}
      def thisNodeConnected(client: ClusterNode) = {}
      def thisNodeDisconnected(client: ClusterNode) = {}
      def thisNodeExpired(client: ClusterNode) = {}
    }

Here is when each callback will be invoked:

  - ``nodeConnected`` -- when a node joins the cluster
  - ``nodeDisconnected`` -- when a node leaves the cluster
  - ``newLeader`` -- when there has been a leader election and the new leader is elected
  - ``thisNodeNewSession`` -- when the local node has created a new session to the cluster
  - ``thisNodeConnected`` -- when a local node has joined the cluster
  - ``thisNodeDisconnected`` -- when a local node has left the cluster
  - ``thisNodeExpired`` -- when the local node's session has expired

If you are using this from Java then you need to use the "Java-friendly"
``ChangeListenerAdapter`` abstract class instead of the ``ChangeListener``
trait.


Clustered Actor Registry
------------------------

You can cluster actors by storing them in the cluster by UUID. The actors will
be serialized deeply (with or without its mailbox and pending messages) and put
in a highly available storage. This actor can then be checked out on any other
node, used there and then checked in again. The cluster will also take care of
transparently migrating actors residing on a failed node onto another node on
the cluster so that the application can continue working as if nothing happened.

Let's look at an example. First we create a simple Hello World actor. We also
create a ``Format`` type class for serialization. For simplicity we are using
plain Java serialization. ::

    import akka.serialization._
    import akka.actor._

    @serializable class HelloActor extends Actor {
      private var count = 0
      self.id = "service:hello"

      def receive = {
        case "hello" =>
          count = count + 1
          self reply ("world " + count)
      }
    }

    object BinaryFormats {
      @serializable implicit object HelloActorFormat
        extends SerializerBasedActorFormat[HelloActor] {
        val serializer = Serializer.Java
      }
    }

|more| Read more about actor serialization in the `Akka Serialization
Documentation <http://doc.akka.io/serialization-scala>`_.

.. todo:: add explanation on how to do this with the Java API

Once we can serialize and deserialize the actor we have what we need in
order to cluster the actor. We have four methods at our disposal:

  - ``store``
  - ``remove``
  - ``use``
  - ``release``


ActorAddress
----------------

The ``ActorAddress`` is used to represent the address to a specific actor. All methods in the API that deals with actors works with ``ActorAddress`` and represents one of these identifiers:

  - ``actorUuid`` -- the UUID for an actor; ``Actor.uuid``
  - ``actorId`` -- the ID for an actor; ``Actor.id``
  - ``actorClassName`` -- the class name of an actor; ``Actor.actorClassName``

To create a ``ActorAddress`` you can create the it using named arguments like this::

    ActorAddress(actorUuid      = uuid)
    ActorAddress(actorId        = id)
    ActorAddress(actorClassName = className)

Or, if you are using the API from Java (or prefer the syntaxt in Scala) then you can use the ``ActorAddress`` factory methods::

    ActorAddress.forUuid(uuid)
    ActorAddress.forId(id)
    ActorAddress.forClassName(className)

Store and Remove
----------------

The methods for storing an actor in the cluster and removing it from the cluster
are:

  - ``store`` -- clusters the actor by adding it to the clustered actor registry, available to any node in the cluster

  - ``remove`` -- removes the actor from the clustered actor registry

The ``store`` method also allows you to specify a replication factor. The
``replicationFactor`` defines the number of (randomly picked) nodes in the cluster that
the stored actor should be automatically deployed to and instantiated  locally on (using
``use``). If you leave this argument out then a replication factor of ``0`` will be used
which means that the actor will only be stored in the clustered actor registry and not
deployed anywhere.

The last argument to the ``store`` method is the ``serializeMailbox`` which defines if
the actor's mailbox should be serialized along with the actor, stored in the cluster and
deployed (if replication factor is set to more than ``0``). If it should or not depends
on your use-case. Default is ``false``

This is the signatures for the ``store`` method (all different permutations of these methods are available for using from Java)::

    def store[T <: Actor]
      (actorRef: ActorRef, replicationFactor: Int = 0, serializeMailbox: Boolean = false)
      (implicit format: Format[T]): ClusterNode

    def store[T <: Actor]
      (actorClass: Class[T], replicationFactor: Int = 0, serializeMailbox: Boolean = false)
      (implicit format: Format[T]): ClusterNode

The ``implicit format: Format[T]`` might look scary but this argument is chosen for you and passed in automatically by the compiler as long as you have imported the serialization typeclass for the actor you are storing, e.g. the ``HelloActorFormat`` (defined above and imported in the sample below).

Here is an example of how to use ``store`` to cluster an already
created actor::

    import Actor._
    import ActorSerialization._
    import BinaryFormats._

    val clusterNode = Cluster.newNode(NodeAddress("test-cluster", "node1")).start

    val hello = actorOf[HelloActor].start.asInstanceOf[LocalActorRef]

    val serializeMailbox = false
    val replicationFactor = 5

    clusterNode store (hello, serializeMailbox, replicationFactor)

Here is an example of how to use ``store`` to cluster an actor by type::

   clusterNode store classOf[HelloActor]

The ``remove`` method allows you to passing in a ``ActorAddress``::

    cluster remove actorAddress

You can also remove an actor by type like this::

    cluster remove classOf[HelloActor]

Use and Release
---------------

The two methods for "checking out" an actor from the cluster for use and
"checking it in" after use are:

  - ``use`` -- "checks out" for use on a specific node, this will deserialize
    the actor and instantiated on the node it is being checked out on

  - ``release`` -- "checks in" the actor after being done with it, important for
    the cluster bookkeeping

The ``use`` and ``release`` methods allow you to pass an instance of ``ActorAddress``. Here is an example::

    val helloActor1 = cluster use actorAddress

    helloActor1 ! "hello"
    helloActor2 ! "hello"
    helloActor3 ! "hello"

    cluster release actorAddress

Ref and Router
--------------

The ``ref`` method is used to create an actor reference to a set of clustered
(remote) actors defined with a spefific routing policy.

This is the signature for ``ref``::

    def ref(actorAddress: ActorAddress, router: Router.RouterType): ActorRef

The final argument ``router`` defines a routing policy in which you have the
following options:

  - ``Router.Direct`` -- this policy means that the reference will only represent one single actor which it will use all the time when sending messages to the actor. If the query returns multiple actors then a single one is picked out randomly.
  - ``Router.Random`` -- this policy will route the messages to a randomly picked actor in the set of actors in the cluster, returned by the query.
  - ``Router.RoundRobin`` -- this policy will route the messages to the set of actors in the cluster returned by the query in a round-robin fashion. E.g. circle around the set of actors in order.

Here is an example::

    // Store the PongActor in the cluster and deploy it to 5 nodes in the cluster
    localNode store (classOf[PongActor], 5)

    // Get a reference to all the pong actors through a round-robin router ActorRef
    val pong = localNode ref (actorAddress, Router.RoundRobin)

    // Send it messages
    pong ! Ping


Actor migration
---------------

The cluster has mechanisms to either manually or automatically fail over all
actors running on a node that have crashed to another node in the cluster. It
will also make sure that all remote clients that are communicating these actors
will automatically and transparently reconnect to the new host node.

Automatic actor migration on fail-over
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

All actors are checked out with ``use`` are tracked by the cluster and will be
automatically failed over to a new node in the cluster if the node that up and
how is it is running on (using it) it crashes. Tracking will stop when the actor
is checked in using ``release``.

Manual actor migration
~~~~~~~~~~~~~~~~~~~~~~

You can move an actor for one node to another using the ``migrate`` method. Here is the parameter list:

  - ``from`` -- the address of the node migrating from (default is the address for the node you are invoking it on)
  - ``to`` -- the address of the node migrating to
  - ``actorAddress`` -- the ``ActorAddress``

Here is an example::

    clusterNode migrate (
      NodeAddress("test-cluster", "node1"),
      NodeAddress("test-cluster", "node2"),
      actorAddress)

Here is an example using ``actorId`` and ``to``, e.g. relying on the default value for ``from`` (this node)::

    clusterNode migrate (NodeAddress("test-cluster", "node2"), actorAddress)

Compute Grid
-----------------------------

Akka can work as a compute grid by allowing you to send functions to the nodes
in the cluster and collect the result back.

The workhorse for this is the ``send`` method (in different variations). The
``send`` methods take the following parameters:
  - ``f`` -- the function you want to be invoked on the remote nodes in the cluster
  - ``arg`` -- the argument to the function (not all of them have this parameter)
  - ``replicationFactor`` -- the replication factor defining the number of nodes you want the function to be sent and invoked on

You can currently send these function types to the cluster:
  - ``Function0[Unit]`` -- takes no arguments and returns nothing
  - ``Function0[Any]`` -- takes no arguments and returns a value of type ``Any``
  - ``Function1[Any, Unit]`` -- takes an arguments of type ``Any`` and returns nothing
  - ``Function1[Any, Any]`` -- takes an arguments of type ``Any`` and returns a value of type ``Any``

All ``send`` methods returns immediately after the functions have been sent off
asynchronously to the remote nodes. The ``send`` methods that takes a function
that yields a return value all return a ``scala.List`` of ``akka.dispatch.Future[Any]``.
This gives you the option of handling these futures the way you wish. Some helper
functions for working with ``Future`` are in the ``akka.dispatch.Futures`` object.

|more| Read more about futures in the `Akka documentation on Futures
<http://doc.akka.io/actors-scala#Actors%20(Scala)-Send%20messages-Send-And-Receive-Future>`_.

Here are some examples showing how you can use the different ``send`` methods.

Send a ``Function0[Unit]``::

    val node1 = Cluster newNode (NodeAddress("test", "node1", port = 9991)) start
    val node2 = Cluster newNode (NodeAddress("test", "node2", port = 9992)) start

    val fun = () => println(">>> AKKA ROCKS <<<")

    // send and invoke function on to two cluster nodes
    node1 send (fun, 2)

Send a ``Function0[Any]``::

    val node1 = Cluster newNode (NodeAddress("test", "node1", port = 9991)) start
    val node2 = Cluster newNode (NodeAddress("test", "node2", port = 9992)) start

    val fun = () => "AKKA ROCKS"

    // send and invoke function on to two cluster nodes and get result
    val futures = node1 send (fun, 2)

    Futures awaitAll futures
    println("Cluster says [" + futures.map(_.result).mkString(" - ") + "]")

Send a ``Function1[Any, Unit]``::

    val node1 = Cluster newNode (NodeAddress("test", "node1", port = 9991)) start
    val node2 = Cluster newNode (NodeAddress("test", "node2", port = 9992)) start

    val fun = ((s: String) => println(">>> " + s + " <<<")).asInstanceOf[Function1[Any, Unit]]

    // send and invoke function on to two cluster nodes
    node1 send (fun, "AKKA ROCKS", 2)

Send a ``Function1[Any, Any]``::

    val node1 = Cluster newNode (NodeAddress("test", "node1", port = 9991)) start
    val node2 = Cluster newNode (NodeAddress("test", "node2", port = 9992)) start

    val fun = ((i: Int) => i * i).asInstanceOf[Function1[Any, Any]]

    // send and invoke function on one cluster node and get result
    val future1 = node1 send (fun, 2, 1) head
    val future2 = node1 send (fun, 2, 1) head

    // grab the result from the first one that returns
    val result = Futures awaitEither (future1, future2)
    println("Cluster says [" + result.get + "]")

Querying the Clustered Actor Registry
-------------------------------------

Here we have some other methods for querying the Clustered Actor Registry in different ways.

Check if an actor is clustered (stored and/or used in the cluster):
  - ``def isClustered(actorUuid: UUID, actorId: String, actorClassName: String): Boolean``
  - When using this method you should only specify one of the parameters using "named parameters" as in the examples above.

Check if an actor is used by a specific node (e.g. checked out locally using ``use``):
  - ``def isInUseOnNode(actorUuid: UUID, actorId: String, actorClassName: String, node: NodeAddress): Boolean``
  - When using this method you should only specify one of the parameters using "named parameters" as in the examples above. Default argument for ``node`` is "this" node.

Lookup the remote addresses for a specific actor (can reside on more than one node):
  - ``def addressesForActor(actorUuid: UUID, actorId: String,
    actorClassName: String): Array[Tuple2[UUID, InetSocketAddress]]``
  - When using this method you should only specify one of the parameters using "named parameters" as in the examples above.

Lookup all actors that are in use (e.g. "checked out") on this node:
  - ``def uuidsForActorsInUse: Array[UUID]``
  - ``def idsForActorsInUse: Array[String]``
  - ``def classNamesForActorsInUse: Array[String]``

Lookup all actors are available (e.g. "stored") in the Clustered Actor Registry:
  - ``def uuidsForClusteredActors: Array[UUID]``
  - ``def idsForClusteredActors: Array[String]``
  - ``def classNamesForClusteredActors: Array[String]``

Lookup the ``Actor.id`` by ``Actor.uuid``:
  - ``def actorIdForUuid(uuid: UUID): String``
  - ``def actorIdsForUuids(uuids: Array[UUID]): Array[String]``

Lookup the ``Actor.actorClassName`` by ``Actor.uuid``:
  - ``def actorClassNameForUuid(uuid: UUID): String``
  - ``def actorClassNamesForUuids(uuids: Array[UUID]): Array[String]``

Lookup the ``Actor.uuid``'s by ``Actor.id``:
  - ``def uuidsForActorId(actorId: String): Array[UUID]``

Lookup the ``Actor.uuid``'s by ``Actor.actorClassName``:
  - ``def uuidsForActorClassName(actorClassName: String): Array[UUID]``

Lookup which nodes that have checked out a specific actor:
  - ``def nodesForActorsInUseWithUuid(uuid: UUID): Array[String]``
  - ``def nodesForActorsInUseWithId(id: String): Array[String]``
  - ``def nodesForActorsInUseWithClassName(className: String): Array[String]``

Lookup the ``Actor.uuid`` for the actors that have been checked out a specific node:
  - ``def uuidsForActorsInUseOnNode(nodeName: String): Array[UUID]``
  - ``def idsForActorsInUseOnNode(nodeName: String): Array[String]``
  - ``def classNamesForActorsInUseOnNode(nodeName: String): Array[String]``

Lookup the serialization ``Format`` instance for a specific actor:
  - ``def formatForActor(actorUuid: UUID, actorId: String, actorClassName: String): Format[T]``
  - When using this method you should only specify one of the parameters using "named parameters" as in the examples above.


Clustered configuration manager
-------------------------------

Custom configuration data
~~~~~~~~~~~~~~~~~~~~~~~~~

You can also store configuration data into the cluster.  This is done using the
``setConfigElement`` and ``getConfigElement`` methods. The key is a ``String`` and the data a ``Array[Byte]``::

    clusterNode setConfigElement ("hello", "world".getBytes("UTF-8"))

    val valueAsBytes  = clusterNode getConfigElement ("hello") // returns Array[Byte]
    val valueAsString = new String(valueAsBytes, "UTF-8")

You can also remove an entry using the ``removeConfigElement`` method and get an
``Array[String]`` with all the keys::

    clusterNode removeConfigElement ("hello")

    val allConfigElementKeys = clusterNode.getConfigElementKeys // returns Array[String]

Consolidation and management of the Akka configuration file
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Not implemented yet.

The actor configuration file ``akka.conf`` will also be stored into the cluster
and it will be possible to have one single configuration file, stored on the server, and pushed out to all
the nodes that joins the cluster. Each node only needs to be configured with the ZooKeeper
server address and the master configuration will only reside in one single place
simplifying administration of the cluster and alleviates the risk of having
different configuration files lying around in the cluster.


Leader election
---------------

The cluster supports leader election. There will always only be one single
leader in the cluster. The first thing that happens when the cluster startup is
a leader election. The leader that gets elected will stay the leader until it
crashes or is shut down, then an automatic reelection process will take place
and a new leader is elected.  Only having one leader in a cluster can be very
useful to solve the wide range of problems. You can find out which node is the
leader by invoking the ``leader`` method. A node can also check if it is the
leader by invoking the ``isLeader`` method. A leader node can also explicitly
resign and issue a new leader election by invoking the ``resign`` method. Each node has an election number stating its ranking in the last election. You can query a node for its election number through the ``electionNumber`` method.


JMX monitoring and management
-----------------------------

.. todo:: Add some docs to each method

The clustering module has an JMX MBean that you can use. Here is the interface
with all available operations::

    trait ClusterNodeMBean {
      def start: Unit
      def stop: Unit

      def disconnect: Unit
      def reconnect: Unit
      def resign: Unit

      def isConnected: Boolean

      def getRemoteServerHostname: String
      def getRemoteServerPort: Int

      def getNodeName: String
      def getClusterName: String
      def getZooKeeperServerAddresses: String

      def getMemberNodes: Array[String]
      def getLeader: String

      def getUuidsForClusteredActors: Array[String]
      def getIdsForClusteredActors: Array[String]
      def getClassNamesForClusteredActors: Array[String]

      def getUuidsForActorsInUse: Array[String]
      def getIdsForActorsInUse: Array[String]
      def getClassNamesForActorsInUse: Array[String]

      def getNodesForActorInUseWithUuid(uuid: String): Array[String]
      def getNodesForActorInUseWithId(id: String): Array[String]
      def getNodesForActorInUseWithClassName(className: String): Array[String]

      def getUuidsForActorsInUseOnNode(nodeName: String): Array[String]
      def getIdsForActorsInUseOnNode(nodeName: String): Array[String]
      def getClassNamesForActorsInUseOnNode(nodeName: String): Array[String]

      def setConfigElement(key: String, value: String): Unit
      def getConfigElement(key: String): AnyRef
      def removeConfigElement(key: String): Unit
      def getConfigElementKeys: Array[String]
    }

JMX support is turned on and off using the default ``akka.enable-jmx``
configuration option.

.. code-block:: conf

    akka {
      enable-jmx = on
    }



.. |more| image:: more.png
          :align: middle
          :alt: More info
