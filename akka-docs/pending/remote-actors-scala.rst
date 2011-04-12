Remote Actors (Scala)
=====================

Module stability: **SOLID**

Akka supports starting Actors and Typed Actors on remote nodes using a very efficient and scalable NIO implementation built upon `JBoss Netty <http://jboss.org/netty>`_ and `Google Protocol Buffers <http://code.google.com/p/protobuf/>`_ .

The usage is completely transparent both in regards to sending messages and error handling and propagation as well as supervision, linking and restarts. You can send references to other Actors as part of the message.

You can find a runnable sample `here <http://github.com/jboner/akka/tree/master/akka-samples/akka-sample-remote/>`_.

Starting up the remote service
------------------------------

Starting remote service in user code as a library
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Here is how to start up the RemoteNode and specify the hostname and port programatically:

.. code-block:: scala

  import akka.actor.Actor._

  remote.start("localhost", 2552)

  // Specify the classloader to use to load the remote class (actor)
  remote.start("localhost", 2552, classLoader)

Here is how to start up the RemoteNode and specify the hostname and port in the 'akka.conf' configuration file (see the section below for details):

.. code-block:: scala

  import akka.actor.Actor._

  remote.start()

  // Specify the classloader to use to load the remote class (actor)
  remote.start(classLoader)

Starting remote service as part of the stand-alone Kernel
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You simply need to make sure that the service is turned on in the external 'akka.conf' configuration file.

.. code-block:: ruby

  akka {
    remote {
      server {
        service = on
        hostname = "localhost"
        port = 2552
        connection-timeout = 1000 # in millis
      }
    }
  }

Stopping a RemoteNode or RemoteServer
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

If you invoke 'shutdown' on the server then the connection will be closed.

.. code-block:: scala

  import akka.actor.Actor._

  remote.shutdown

Connecting and shutting down a client explicitly
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Normally you should not have to start and stop the client connection explicitly since that is handled by Akka on a demand basis. But if you for some reason want to do that then you can do it like this:

.. code-block:: scala

  import akka.actor.Actor._

  remote.shutdownClientConnection(new InetSocketAddress("localhost", 6666)) //Returns true if successful, false otherwise
  remote.restartClientConnection(new InetSocketAddress("localhost", 6666)) //Returns true if successful, false otherwise

Remote Client message frame size configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can define the max message frame size for the remote messages:

.. code-block:: ruby

  akka {
    remote {
      client {
        message-frame-size = 1048576
      }
    }
  }

Remote Client reconnect configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Remote Client automatically performs reconnection upon connection failure.

You can configure it like this:

.. code-block:: ruby

  akka {
    remote {
      client {
        reconnect-delay = 5000    # in millis (5 sec default)
        read-timeout = 10000      # in millis (10 sec default)
        reconnection-time-window = 600 # the maximum time window that a client should try to reconnect for
      }
    }
  }

The RemoteClient is automatically trying to reconnect to the server if the connection is broken. By default it has a reconnection window of 10 minutes (600 seconds).

If it has not been able to reconnect during this period of time then it is shut down and further attempts to use it will yield a 'RemoteClientException'. The 'RemoteClientException' contains the message as well as a reference to the RemoteClient that is not yet connect in order for you to retrieve it an do an explicit connect if needed.

You can also register a listener that will listen for example the 'RemoteClientStopped' event, retrieve the 'RemoteClient' from it and reconnect explicitly.

See the section on RemoteClient listener and events below for details.

Remote Client message buffering and send retry on failure
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Remote Client implements message buffering on network failure. This feature has zero overhead (even turned on) in the successful scenario and a queue append operation in case of unsuccessful send. So it is really really fast.

The default behavior is that the remote client will maintain a transaction log of all messages that it has failed to send due to network problems (not other problems like serialization errors etc.).  The client will try to resend these messages upon first successful reconnect and the message ordering is maintained. This means that the remote client will swallow all exceptions due to network failure and instead queue remote messages in the transaction log. The failures will however be reported through the remote client life-cycle events as well as the regular Akka event handler. You can turn this behavior on and off in the configuration file. It gives 'at-least-once' semantics, use a message id/counter for discarding potential duplicates (or use idempotent messages).

.. code-block:: ruby

  akka {
    remote {
      client {
        buffering {
          retry-message-send-on-failure = on
          capacity = -1                      # If negative (or zero) then an unbounded mailbox is used (default)
                                             # If positive then a bounded mailbox is used and the capacity is set using the property
        }
      }
    }
  }

If you choose a capacity higher than 0, then a bounded queue will be used and if the limit of the queue is reached then a 'RemoteClientMessageBufferException' will be thrown.

You can also get an Array with all the messages that the remote client has failed to send. Since the remote client events passes you an instance of the RemoteClient you have an easy way to act upon failure and do something with these messages (while waiting for them to be retried).

.. code-block:: scala

  val pending: Array[Any] = Actor.remote.pendingMessages

Running Remote Server in untrusted mode
---------------------------------------

You can run the remote server in untrusted mode. This means that the server will not allow any client-managed remote actors or any life-cycle messages and methods. This is useful if you want to let untrusted clients use server-managed actors in a safe way. This can optionally be combined with the secure cookie authentication mechanism described below as well as the SSL support for remote actor communication.

If the client is trying to perform one of these unsafe actions then a 'java.lang.SecurityException' is thrown on the server as well as transferred to the client and thrown there as well.

Here is how you turn it on:

.. code-block:: ruby

  akka {
    remote {
      server {
        untrusted-mode = on # the default is 'off'
      }
    }
  }

The messages that it prevents are all that extends 'LifeCycleMessage':
* class HotSwap(..)
* class RevertHotSwap..)
* class Restart(..)
* class Exit(..)
* class Link(..)
* class Unlink(..)
* class UnlinkAndStop(..)
* class ReceiveTimeout..)

It also prevents the client from invoking any life-cycle and side-effecting methods, such as:
* start
* stop
* link
* unlink
* spawnLink
* etc.

Using secure cookie for remote client authentication
----------------------------------------------------

Akka is using a similar scheme for remote client node authentication as Erlang; using secure cookies. In order to use this authentication mechanism you have to do two things:

* Enable secure cookie authentication in the remote server
* Use the same secure cookie on all the trusted peer nodes

Enabling secure cookie authentication
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The first one is done by enabling the secure cookie authentication in the remote server section in the configuration file:

.. code-block:: ruby

  akka {
    remote {
      server {
        require-cookie = on
      }
    }
  }

Now if you have try to connect to a server with a client then it will first try to authenticate the client by comparing the secure cookie for the two nodes. If they are the same then it allows the client to connect and use the server freely but if they are not the same then it will throw a 'java.lang.SecurityException' and not allow the client to connect.

Generating and using the secure cookie
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The secure cookie can be any string value but in order to ensure that it is secure it is best to randomly generate it. This can be done by invoking the 'generate_config_with_secure_cookie.sh' script which resides in the '$AKKA_HOME/scripts' folder. This script will generate and print out a complete 'akka.conf' configuration file with the generated secure cookie defined that you can either use as-is or cut and paste the 'secure-cookie' snippet. Here is an example of its generated output:

.. code-block:: ruby

  # This config imports the Akka reference configuration.
  include "akka-reference.conf"

  # In this file you can override any option defined in the 'akka-reference.conf' file.
  # Copy in all or parts of the 'akka-reference.conf' file and modify as you please.

  akka {
    remote {
      secure-cookie = "000E02050F0300040C050C0D060A040306090B0C"
    }
  }

The simplest way to use it is to have it create your 'akka.conf' file like this:

.. code-block:: ruby

  cd $AKKA_HOME
  ./scripts/generate_config_with_secure_cookie.sh > ./config/akka.conf

Now it is good to make sure that the configuration file is only accessible by the owner of the file. On Unix-style file system this can be done like this:

.. code-block:: ruby

  chmod 400 ./config/akka.conf

Running this script requires having 'scala' on the path (and will take a couple of seconds to run since it is using Scala and has to boot up the JVM to run).

You can also generate the secure cookie by using the 'Crypt' object and its 'generateSecureCookie' method.

.. code-block:: scala

  import akka.util.Crypt

  val secureCookie = Crypt.generateSecureCookie

The secure cookie is a cryptographically secure randomly generated byte array turned into a SHA-1 hash.

Remote Actors
-------------

Akka has two types of remote actors:

* Client-initiated and managed. Here it is the client that creates the remote actor and "moves it" to the server.
* Server-initiated and managed. Here it is the server that creates the remote actor and the client can ask for a handle to this actor.

They are good for different use-cases. The client-initiated are great when you want to monitor an actor on another node since it allows you to link to it and supervise it using the regular supervision semantics. They also make RPC completely transparent. The server-initiated, on the other hand, are great when you have a service running on the server that you want clients to connect to, and you want full control over the actor on the server side for security reasons etc.

Client-managed Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

DEPRECATED AS OF 1.1

When you define an actors as being remote it is instantiated as on the remote host and your local actor becomes a proxy, it works as a handle to the remote actor. The real execution is always happening on the remote node.

Actors can be made remote by calling remote().actorOf[MyActor](host, port)

Here is an example:

.. code-block:: scala

  import akka.actor.Actor

  class MyActor extends RemoteActor() {
    def receive = {
      case  "hello" => self.reply("world")
    }
  }

  val remote = Actor.remote().actorOf[MyActor]("192.68.23.769", 2552)

An Actor can also start remote child Actors through one of the 'spawn/link' methods. These will start, link and make the Actor remote atomically.

.. code-block:: scala

  ...
  spawnRemote[MyActor](hostname, port)
  spawnLinkRemote[MyActor](hostname, port)
  ...

Server-managed Remote Actors
----------------------------

Server side setup
^^^^^^^^^^^^^^^^^

The API for server managed remote actors is really simple. 2 methods only:

.. code-block:: scala

  class HelloWorldActor extends Actor {
    def receive = {
      case "Hello" => self.reply("World")
    }
  }

  remote.start("localhost", 2552) //Start the server
  remote.register("hello-service", actorOf[HelloWorldActor]) //Register the actor with the specified service id

Actors created like this are automatically started.

You can also register an actor by its UUD rather than ID or handle. This is done by prefixing the handle with the "uuid:" protocol.

.. code-block:: scala

  remote.register("uuid:" + actor.uuid, actor)

  remote.unregister("uuid:" + actor.uuid)

Session bound server side setup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Session bound server managed remote actors work by creating and starting a new actor for every client that connects. Actors are stopped automatically when the client disconnects. The client side is the same as regular server managed remote actors. Use the function registerPerSession instead of register.

Session bound actors are useful if you need to keep state per session, e.g. username.
They are also useful if you need to perform some cleanup when a client disconnects by overriding the postStop method as described `here <actors-scala#Stopping actors>`_

.. code-block:: scala

  class HelloWorldActor extends Actor {
    def receive = {
      case "Hello" => self.reply("World")
    }
  }
  remote.start("localhost", 2552)
  remote.registerPerSession("hello-service", actorOf[HelloWorldActor])

Note that the second argument in registerPerSession is an implicit function. It will be called to create an actor every time a session is established.

Client side usage
^^^^^^^^^^^^^^^^^

.. code-block:: scala

  val actor = remote.actorFor("hello-service", "localhost", 2552)
  val result = actor !! "Hello"

There are many variations on the 'remote#actorFor' method. Here are some of them:

.. code-block:: scala

  ... = actorFor(className, hostname, port)
  ... = actorFor(className, timeout, hostname, port)
  ... = actorFor(uuid, className, hostname, port)
  ... = actorFor(uuid, className, timeout, hostname, port)
  ... // etc

All of these also have variations where you can pass in an explicit 'ClassLoader' which can be used when deserializing messages sent from the remote actor.

Running sample
^^^^^^^^^^^^^^

Here is a complete running sample (also available `here <http://github.com/jboner/akka/blob/master/akka-core/src/test/scala/ServerInitiatedRemoteActorSample.scala>`_):

.. code-block:: scala

  import akka.actor.Actor
  import akka.util.Logging
  import Actor._

  class HelloWorldActor extends Actor {
    def receive = {
      case "Hello" => self.reply("World")
    }
  }

  object ServerInitiatedRemoteActorServer {

    def run = {
      remote.start("localhost", 2552)
      remote.register("hello-service", actorOf[HelloWorldActor])
    }

    def main(args: Array[String]) = run
  }

  object ServerInitiatedRemoteActorClient extends Logging {

    def run = {
      val actor = remote.actorFor("hello-service", "localhost", 2552)
      val result = actor !! "Hello"
      log.info("Result from Remote Actor: %s", result)
    }

    def main(args: Array[String]) = run
  }

Automatic remote 'sender' reference management
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The sender of a remote message will be reachable with a reply through the remote server on the node that the actor is residing, automatically.
Please note that firewalled clients won't work right now. [2011-01-05]

Identifying remote actors
-------------------------

The 'id' field in the 'Actor' class is of importance since it is used as identifier for the remote actor. If you want to create a brand new actor every time you instantiate a remote actor then you have to set the 'id' field to a unique 'String' for each instance. If you want to reuse the same remote actor instance for each new remote actor (of the same class) you create then you don't have to do anything since the 'id' field by default is equal to the name of the actor class.

Here is an example of overriding the 'id' field:

.. code-block:: scala

  import akka.util.UUID

  class MyActor extends Actor {
    self.id = UUID.newUuid.toString
    def receive = {
      case  "hello" =>  self.reply("world")
    }
  }

  val actor = remote.actorOf[MyActor]("192.68.23.769", 2552)

Remote Typed Actors
-------------------

Client-managed Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

DEPRECATED AS OF 1.1

You can define the Typed Actor to be a remote service by adding the 'RemoteAddress' configuration element in the declarative supervisor configuration:

.. code-block:: java

  new Component(
    Foo.class,
    new LifeCycle(new Permanent(), 1000),
    1000,
    new RemoteAddress("localhost", 2552))

You can also define an Typed Actor to be remote programmatically when creating it explicitly:

.. code-block:: java

  TypedActorFactory factory = new TypedActorFactory();

  POJO pojo = (POJO) factory.newRemoteInstance(POJO.class, 1000, "localhost", 2552)

  ... // use pojo as usual

Server-managed Remote Actors
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

WARNING: Remote TypedActors do not work with overloaded methods on your TypedActor, refrain from using overloading.

Server side setup
*****************

The API for server managed remote typed actors is nearly the same as for untyped actor

.. code-block:: scala

  class RegistrationServiceImpl extends TypedActor with RegistrationService {
    def registerUser(user: User): Unit = {
      ... // register user
    }
  }

  remote.start("localhost", 2552)

  val typedActor = TypedActor.newInstance(classOf[RegistrationService], classOf[RegistrationServiceImpl], 2000)
  remote.registerTypedActor("user-service", typedActor)

Actors created like this are automatically started.

Session bound server side setup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Session bound server managed remote actors work by creating and starting a new actor for every client that connects. Actors are stopped automatically when the client disconnects. The client side is the same as regular server managed remote actors. Use the function registerTypedPerSessionActor instead of registerTypedActor.

Session bound actors are useful if you need to keep state per session, e.g. username.
They are also useful if you need to perform some cleanup when a client disconnects.

.. code-block:: scala

  class RegistrationServiceImpl extends TypedActor with RegistrationService {
    def registerUser(user: User): Unit = {
      ... // register user
    }
  }
  remote.start("localhost", 2552)

  remote.registerTypedPerSessionActor("user-service",
     TypedActor.newInstance(classOf[RegistrationService],
      classOf[RegistrationServiceImpl], 2000))

Note that the second argument in registerTypedPerSessionActor is an implicit function. It will be called to create an actor every time a session is established.

Client side usage
*****************

.. code-block:: scala

  val actor = remote.typedActorFor(classOf[RegistrationService], "user-service", 5000L, "localhost", 2552)
  actor.registerUser(…)

There are variations on the 'RemoteClient#typedActorFor' method. Here are some of them:

.. code-block:: scala

  ... = typedActorFor(interfaceClazz, serviceIdOrClassName, hostname, port)
  ... = typedActorFor(interfaceClazz, serviceIdOrClassName, timeout, hostname, port)
  ... = typedActorFor(interfaceClazz, serviceIdOrClassName, timeout, hostname, port, classLoader)

Data Compression Configuration
------------------------------

Akka uses compression to minimize the size of the data sent over the wire. Currently it only supports 'zlib' compression but more will come later.

You can configure it like this:

.. code-block:: ruby

  akka {
    remote {
      compression-scheme = "zlib" # Options: "zlib" (lzf to come), leave out for no compression
      zlib-compression-level = 6  # Options: 0-9 (1 being fastest and 9 being the most compressed), default is 6
    }
  }

Code provisioning
-----------------

Akka does currently not support automatic code provisioning but requires you to have the remote actor class files available on both the "client" the "server" nodes.
This is something that will be addressed soon. Until then, sorry for the inconvenience.

Subscribe to Remote Client events
---------------------------------

Akka has a subscription API for the client event. You can register an Actor as a listener and this actor will have to be able to process these events:

.. code-block:: scala

  sealed trait RemoteClientLifeCycleEvent
  case class RemoteClientError(
    @BeanProperty cause: Throwable,
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

  case class RemoteClientDisconnected(
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

  case class RemoteClientConnected(
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

  case class RemoteClientStarted(
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

  case class RemoteClientShutdown(
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

  case class RemoteClientWriteFailed(
    @BeanProperty request: AnyRef,
    @BeanProperty cause: Throwable,
    @BeanProperty client: RemoteClientModule,
    @BeanProperty remoteAddress: InetSocketAddress) extends RemoteClientLifeCycleEvent

So a simple listener actor can look like this:

.. code-block:: scala

  val listener = actorOf(new Actor {
    def receive = {
      case RemoteClientError(cause, client, address) => ... // act upon error
      case RemoteClientDisconnected(client, address) => ... // act upon disconnection
      case RemoteClientConnected(client, address)    => ... // act upon connection
      case RemoteClientStarted(client, address)      => ... // act upon client shutdown
      case RemoteClientShutdown(client, address)     => ... // act upon client shutdown
      case RemoteClientWriteFailed(request, cause, client, address) => ... // act upon write failure
      case _ => //ignore other
    }
  }).start()

Registration and de-registration can be done like this:

.. code-block:: scala

  remote.addListener(listener)
  ...
  remote.removeListener(listener)

Subscribe to Remote Server events
---------------------------------

Akka has a subscription API for the 'RemoteServer'. You can register an Actor as a listener and this actor will have to be able to process these events:

.. code-block:: scala

  sealed trait RemoteServerLifeCycleEvent
  case class RemoteServerStarted(
    @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
  case class RemoteServerShutdown(
    @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
  case class RemoteServerError(
    @BeanProperty val cause: Throwable,
    @BeanProperty val server: RemoteServerModule) extends RemoteServerLifeCycleEvent
  case class RemoteServerClientConnected(
    @BeanProperty val server: RemoteServerModule,
    @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
  case class RemoteServerClientDisconnected(
    @BeanProperty val server: RemoteServerModule,
    @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
  case class RemoteServerClientClosed(
    @BeanProperty val server: RemoteServerModule,
    @BeanProperty val clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent
  case class RemoteServerWriteFailed(
    @BeanProperty request: AnyRef,
    @BeanProperty cause: Throwable,
    @BeanProperty server: RemoteServerModule,
    @BeanProperty clientAddress: Option[InetSocketAddress]) extends RemoteServerLifeCycleEvent

So a simple listener actor can look like this:

.. code-block:: scala

  val listener = actorOf(new Actor {
    def receive = {
      case RemoteServerStarted(server)                           => ... // act upon server start
      case RemoteServerShutdown(server)                          => ... // act upon server shutdown
      case RemoteServerError(cause, server)                      => ... // act upon server error
      case RemoteServerClientConnected(server, clientAddress)    => ... // act upon client connection
      case RemoteServerClientDisconnected(server, clientAddress) => ... // act upon client disconnection
      case RemoteServerClientClosed(server, clientAddress)       => ... // act upon client connection close
      case RemoteServerWriteFailed(request, casue, server, clientAddress) => ... // act upon server write failure
    }
  }).start()

Registration and de-registration can be done like this:

.. code-block:: scala

  remote.addListener(listener)
  ...
  remote.removeListener(listener)

Message Serialization
---------------------

All messages that are sent to remote actors needs to be serialized to binary format to be able to travel over the wire to the remote node. This is done by letting your messages extend one of the traits in the 'akka.serialization.Serializable' object. If the messages don't implement any specific serialization trait then the runtime will try to use standard Java serialization.

Here are some examples, but full documentation can be found in the `Serialization section <serialization>`_.

Scala JSON
^^^^^^^^^^

.. code-block:: scala

  case class MyMessage(id: String, value: Tuple2[String, Int]) extends Serializable.ScalaJSON[MyMessage]

Protobuf
^^^^^^^^

Protobuf message specification needs to be compiled with 'protoc' compiler.

.. code-block:: scala

  message ProtobufPOJO {
    required uint64 id = 1;
    required string name = 2;
    required bool status = 3;
  }

Using the generated message builder to send the message to a remote actor:

.. code-block:: scala

  val result = actor !! ProtobufPOJO.newBuilder
      .setId(11)
      .setStatus(true)
      .setName("Coltrane")
      .build

SBinary
^^^^^^^

`<code format="scala">`_
case class User(firstNameLastName: Tuple2[String, String], email: String, age: Int) extends Serializable.SBinary[User] {
  import sbinary.DefaultProtocol._

  def this() = this(null, null, 0)

  implicit object UserFormat extends Format[User] {
    def reads(in : Input) = User(
      read[Tuple2[String, String]](in),
      read[String](in),
      read[Int](in))
    def writes(out: Output, value: User) = {
      write[Tuple2[String, String]](out, value. firstNameLastName)
      write[String](out, value.email)
      write[Int](out, value.age)
    }
  }

  def fromBytes(bytes: Array[Byte]) = fromByteArray[User](bytes)

  def toBytes: Array[Byte] = toByteArray(this)
}
`<code>`_
