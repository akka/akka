.. _remote-actors-java:

Remote Actors (Java)
====================

.. sidebar:: Contents

   .. contents:: :local:
   
Module stability: **SOLID**

Akka supports starting interacting with UntypedActors and TypedActors on remote nodes using a very efficient and scalable NIO implementation built upon `JBoss Netty <http://jboss.org/netty>`_ and `Google Protocol Buffers <http://code.google.com/p/protobuf/>`_ .

The usage is completely transparent with local actors, both in regards to sending messages and error handling and propagation as well as supervision, linking and restarts. You can send references to other Actors as part of the message.

**WARNING**: For security reasons, do not run an Akka node with a Remote Actor port reachable by untrusted connections unless you have supplied a classloader that restricts access to the JVM.

Managing the Remote Service
---------------------------

Starting remote service in user code as a library
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Here is how to start up the server and specify the hostname and port programmatically:

.. code-block:: java

  import static akka.actor.Actors.*;

  remote().start("localhost", 2552);

  // Specify the classloader to use to load the remote class (actor)
  remote().start("localhost", 2552, classLoader);

Here is how to start up the server and specify the hostname and port in the ‘akka.conf’ configuration file (see the section below for details):

.. code-block:: java

  import static akka.actor.Actors.*;

  remote().start();

  // Specify the classloader to use to load the remote class (actor)
  remote().start(classLoader);

Starting remote service as part of the stand-alone Kernel
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You simply need to make sure that the service is turned on in the external ‘akka.conf’ configuration file.

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

Stopping the server
^^^^^^^^^^^^^^^^^^^

.. code-block:: java

  import static akka.actor.Actors.*;

  remote().shutdown();

Connecting and shutting down a client connection explicitly
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Normally you should not have to start and stop the client connection explicitly since that is handled by Akka on a demand basis. But if you for some reason want to do that then you can do it like this:

.. code-block:: java

  import static akka.actor.Actors.*;
  import java.net.InetSocketAddress;

  remote().shutdownClientConnection(new InetSocketAddress("localhost", 6666)); //Returns true if successful, else false
  remote().restartClientConnection(new InetSocketAddress("localhost", 6666)); //Returns true if successful, else false

Client message frame size configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can define the max message frame size for the remote messages:

.. code-block:: ruby

  akka {
    remote {
      client {
        message-frame-size = 1048576
      }
    }
  }

Client reconnect configuration
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The Client automatically performs reconnection upon connection failure.

You can configure it like this:

.. code-block:: ruby

  akka {
    remote {
      client {
        reconnect-delay = 5            # in seconds (5 sec default)
        read-timeout = 10              # in seconds (10 sec default)
        reconnection-time-window = 600 # the maximum time window that a client should try to reconnect for
      }
    }
  }

The client will automatically trying to reconnect to the server if the connection is broken. By default it has a reconnection window of 10 minutes (600 seconds).

If it has not been able to reconnect during this period of time then it is shut down and further attempts to use it will yield a 'RemoteClientException'. The 'RemoteClientException' contains the message as well as a reference to the address that is not yet connect in order for you to retrieve it an do an explicit connect if needed.

You can also register a listener that will listen for example the 'RemoteClientStopped' event, retrieve the address that got disconnected and reconnect explicitly.

See the section on client listener and events below for details.

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
* case class HotSwap(..)
* case object RevertHotSwap
* case class Restart(..)
* case class Exit(..)
* case class Link(..)
* case class Unlink(..)
* case class UnlinkAndStop(..)
* case object ReceiveTimeout

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

Now if you have try to connect to a server from a client then it will first try to authenticate the client by comparing the secure cookie for the two nodes. If they are the same then it allows the client to connect and use the server freely but if they are not the same then it will throw a 'java.lang.SecurityException' and not allow the client to connect.

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

  import akka.util.Crypt;

  String secureCookie = Crypt.generateSecureCookie();

The secure cookie is a cryptographically secure randomly generated byte array turned into a SHA-1 hash.

Client-managed Remote UntypedActor
----------------------------------

DEPRECATED AS OF 1.1

The client creates the remote actor and "moves it" to the server.

When you define an actors as being remote it is instantiated as on the remote host and your local actor becomes a proxy, it works as a handle to the remote actor. The real execution is always happening on the remote node.

Here is an example:

.. code-block:: java

  import akka.actor.UntypedActor;
  import static akka.actor.Actors.*;

  class MyActor extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      ...
    }
  }

  //How to make it client-managed:
  remote().actorOf(MyActor.class,"192.68.23.769", 2552);

An UntypedActor can also start remote child Actors through one of the “spawn/link” methods. These will start, link and make the UntypedActor remote atomically.

.. code-block:: java

  ...
  getContext().spawnRemote(MyActor.class, hostname, port, timeoutInMsForFutures);
  getContext().spawnLinkRemote(MyActor.class, hostname, port, timeoutInMsForFutures);
  ...

Server-managed Remote UntypedActor
----------------------------------

Here it is the server that creates the remote actor and the client can ask for a handle to this actor.

Server side setup
^^^^^^^^^^^^^^^^^

The API for server managed remote actors is really simple. 2 methods only:

.. code-block:: java

  import akka.actor.Actors;
  import akka.actor.UntypedActor;

  class MyActor extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      ...
    }
  }
  Actors.remote().start("localhost", 2552).register("hello-service", Actors.actorOf(HelloWorldActor.class));

Actors created like this are automatically started.

You can also register an actor by its UUID rather than ID or handle. This is done by prefixing the handle with the "uuid:" protocol.

.. code-block:: scala

  server.register("uuid:" + actor.uuid, actor);

  server.unregister("uuid:" + actor.uuid);

Session bound server side setup
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Session bound server managed remote actors work by creating and starting a new actor for every client that connects. Actors are stopped automatically when the client disconnects. The client side is the same as regular server managed remote actors. Use the function registerPerSession instead of register.

Session bound actors are useful if you need to keep state per session, e.g. username. They are also useful if you need to perform some cleanup when a client disconnects by overriding the postStop method as described `here <actors-scala#Stopping actors>`_

.. code-block:: java

  import static akka.actor.Actors.*;
  import akka.japi.Creator;

  class HelloWorldActor extends Actor {
    ...
  }

  remote().start("localhost", 2552);

  remote().registerPerSession("hello-service", new Creator<ActorRef>() {
    public ActorRef create() {
      return actorOf(HelloWorldActor.class);
    }
  });

Note that the second argument in registerPerSession is a Creator, it means that the create method will create a new ActorRef each invocation.
It will be called to create an actor every time a session is established.

Client side usage
^^^^^^^^^^^^^^^^^

.. code-block:: java

  import static akka.actor.Actors.*;
  ActorRef actor = remote().actorFor("hello-service", "localhost", 2552);

  Object result = actor.sendRequestReply("Hello");

There are many variations on the 'remote()#actorFor' method. Here are some of them:

.. code-block:: java

  ... = remote().actorFor(className, hostname, port);
  ... = remote().actorFor(className, timeout, hostname, port);
  ... = remote().actorFor(uuid, className, hostname, port);
  ... = remote().actorFor(uuid, className, timeout, hostname, port);
  ... // etc

All of these also have variations where you can pass in an explicit 'ClassLoader' which can be used when deserializing messages sent from the remote actor.

Automatic remote 'sender' reference management
----------------------------------------------

The sender of a remote message will be reachable with a reply through the remote server on the node that the actor is residing, automatically.
Please note that firewalled clients won't work right now. [2011-01-05]

Identifying remote actors
-------------------------

The 'id' field in the 'Actor' class is of importance since it is used as identifier for the remote actor. If you want to create a brand new actor every time you instantiate a remote actor then you have to set the 'id' field to a unique 'String' for each instance. If you want to reuse the same remote actor instance for each new remote actor (of the same class) you create then you don't have to do anything since the 'id' field by default is equal to the name of the actor class.

Here is an example of overriding the 'id' field:

.. code-block:: java

  import akka.actor.UntypedActor;
  import com.eaio.uuid.UUID;

  class MyActor extends UntypedActor {
    public MyActor() {
      getContext().setId(new UUID().toString());
    }

    public void onReceive(Object message) throws Exception {
      // ...
    }
  }

Client-managed Remote Typed Actors
----------------------------------

DEPRECATED AS OF 1.1

Remote Typed Actors are created through the 'TypedActor.newRemoteInstance' factory method.

.. code-block:: java

  MyPOJO remoteActor = (MyPOJO) TypedActor.newRemoteInstance(MyPOJO.class, MyPOJOImpl.class, "localhost", 2552);

And if you want to specify the timeout:

.. code-block:: java

  MyPOJO remoteActor = (MyPOJO)TypedActor.newRemoteInstance(MyPOJO.class, MyPOJOImpl.class, timeout, "localhost", 2552);

You can also define the Typed Actor to be a client-managed-remote service by adding the ‘RemoteAddress’ configuration element in the declarative supervisor configuration:

.. code-block:: java

  new Component(
    Foo.class,
    FooImpl.class,
    new LifeCycle(new Permanent(), 1000),
    1000,
    new RemoteAddress("localhost", 2552))

Server-managed Remote Typed Actors
----------------------------------

WARNING: Remote TypedActors do not work with overloaded methods on your TypedActor, refrain from using overloading.

Server side setup
^^^^^^^^^^^^^^^^^

The API for server managed remote typed actors is nearly the same as for untyped actor:

.. code-block:: java

  import static akka.actor.Actors.*;
  remote().start("localhost", 2552);

  RegistrationService typedActor = TypedActor.newInstance(RegistrationService.class, RegistrationServiceImpl.class, 2000);
  remote().registerTypedActor("user-service", typedActor);


Client side usage
^^^^^^^^^^^^^^^^^

.. code-block:: java

  import static akka.actor.Actors.*;
  RegistrationService actor = remote().typedActorFor(RegistrationService.class, "user-service", 5000L, "localhost", 2552);
  actor.registerUser(...);

There are variations on the 'remote()#typedActorFor' method. Here are some of them:

.. code-block:: java

  ... = remote().typedActorFor(interfaceClazz, serviceIdOrClassName, hostname, port);
  ... = remote().typedActorFor(interfaceClazz, serviceIdOrClassName, timeout, hostname, port);
  ... = remote().typedActorFor(interfaceClazz, serviceIdOrClassName, timeout, hostname, port, classLoader);

Data Compression Configuration
------------------------------

Akka uses compression to minimize the size of the data sent over the wire. Currently it only supports 'zlib' compression but more will come later.

You can configure it like this:

.. code-block:: ruby

  akka {
    remote {
      compression-scheme = "zlib" # Options: "zlib" (lzf to come), leave out for no compression
      zlib-compression-level = 6  # Options: 0-9 (1 being fastest and 9 being the most compressed), default is 6

      ...
    }
  }

Code provisioning
-----------------

Akka does currently not support automatic code provisioning but requires you to have the remote actor class files available on both the "client" the "server" nodes.
This is something that will be addressed soon. Until then, sorry for the inconvenience.

Subscribe to Remote Client events
---------------------------------

Akka has a subscription API for remote client events. You can register an Actor as a listener and this actor will have to be able to process these events:

.. code-block:: java

  class RemoteClientError { Throwable cause; RemoteClientModule client; InetSocketAddress remoteAddress; }
  class RemoteClientDisconnected { RemoteClientModule client; InetSocketAddress remoteAddress; }
  class RemoteClientConnected { RemoteClientModule client; InetSocketAddress remoteAddress; }
  class RemoteClientStarted { RemoteClientModule client; InetSocketAddress remoteAddress; }
  class RemoteClientShutdown { RemoteClientModule client; InetSocketAddress remoteAddress; }
  class RemoteClientWriteFailed { Object message; Throwable cause; RemoteClientModule client; InetSocketAddress remoteAddress; }

So a simple listener actor can look like this:

.. code-block:: java

  import akka.actor.UntypedActor;
  import akka.remoteinterface.*;
  
  class Listener extends UntypedActor {

    public void onReceive(Object message) throws Exception {
      if (message instanceof RemoteClientError) {
        RemoteClientError event = (RemoteClientError) message;
        Throwable cause = event.getCause();
        // ...
      } else if (message instanceof RemoteClientConnected) {
        RemoteClientConnected event = (RemoteClientConnected) message;
        // ...
      } else if (message instanceof RemoteClientDisconnected) {
        RemoteClientDisconnected event = (RemoteClientDisconnected) message;
        // ...
      } else if (message instanceof RemoteClientStarted) {
        RemoteClientStarted event = (RemoteClientStarted) message;
        // ...
      } else if (message instanceof RemoteClientShutdown) {
        RemoteClientShutdown event = (RemoteClientShutdown) message;
        // ...
      } else if (message instanceof RemoteClientWriteFailed) {
        RemoteClientWriteFailed event = (RemoteClientWriteFailed) message;
        // ...
      }
    }
  }

Registration and de-registration can be done like this:

.. code-block:: java

  ActorRef listener = Actors.actorOf(Listener.class);
  ...
  Actors.remote().addListener(listener);
  ...
  Actors.remote().removeListener(listener);

Subscribe to Remote Server events
---------------------------------

Akka has a subscription API for the server events. You can register an Actor as a listener and this actor will have to be able to process these events:

.. code-block:: java

  class RemoteServerStarted { RemoteServerModule server; }
  class RemoteServerShutdown { RemoteServerModule server; }
  class RemoteServerError { Throwable cause; RemoteServerModule server; }
  class RemoteServerClientConnected { RemoteServerModule server; Option<InetSocketAddress> clientAddress; }
  class RemoteServerClientDisconnected { RemoteServerModule server; Option<InetSocketAddress> clientAddress; }
  class RemoteServerClientClosed { RemoteServerModule server; Option<InetSocketAddress> clientAddress; }
  class RemoteServerWriteFailed { Object request; Throwable cause; RemoteServerModule server; Option<InetSocketAddress> clientAddress; }

So a simple listener actor can look like this:

.. code-block:: java

  import akka.actor.UntypedActor;
  import akka.remoteinterface.*;

  class Listener extends UntypedActor {

    public void onReceive(Object message) throws Exception {
      if (message instanceof RemoteClientError) {
        RemoteClientError event = (RemoteClientError) message;
        Throwable cause = event.getCause();
        // ...
      } else if (message instanceof RemoteClientConnected) {
        RemoteClientConnected event = (RemoteClientConnected) message;
        // ...
      } else if (message instanceof RemoteClientDisconnected) {
        RemoteClientDisconnected event = (RemoteClientDisconnected) message;
        // ...
      } else if (message instanceof RemoteClientStarted) {
        RemoteClientStarted event = (RemoteClientStarted) message;
        // ...
      } else if (message instanceof RemoteClientShutdown) {
        RemoteClientShutdown event = (RemoteClientShutdown) message;
        // ...
      } else if (message instanceof RemoteClientWriteFailed) {
        RemoteClientWriteFailed event = (RemoteClientWriteFailed) message;
        // ...
      }
    }
  }

Registration and de-registration can be done like this:

.. code-block:: java

  import static akka.actor.Actors.*;

  ActorRef listener = actorOf(Listener.class);
  ...
  remote().addListener(listener);
  ...
  remote().removeListener(listener);

Message Serialization
---------------------

All messages that are sent to remote actors needs to be serialized to binary format to be able to travel over the wire to the remote node. This is done by letting your messages extend one of the traits in the 'akka.serialization.Serializable' object. If the messages don't implement any specific serialization trait then the runtime will try to use standard Java serialization.

Here is one example, but full documentation can be found in the :ref:`serialization-java`.

Protobuf
^^^^^^^^

Protobuf message specification needs to be compiled with 'protoc' compiler.

::

  message ProtobufPOJO {
    required uint64 id = 1;
    required string name = 2;
    required bool status = 3;
  }

Using the generated message builder to send the message to a remote actor:

.. code-block:: java

  actor.tell(ProtobufPOJO.newBuilder()
      .setId(11)
      .setStatus(true)
      .setName("Coltrane")
      .build());
