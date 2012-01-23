Tutorial: write a scalable, fault-tolerant, network chat server and client (Scala)
=============================================================================================

.. sidebar:: Contents

   .. contents:: :local:
   
Introduction
------------

`Tutorial source code <https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-chat/src/main/scala/ChatServer.scala>`_.

Writing correct concurrent, fault-tolerant and scalable applications is too hard. Most of the time it's because we are using the wrong tools and the wrong level of abstraction.

`Akka <http://akka.io>`_ is an attempt to change that.

Akka uses the Actor Model together with Software Transactional Memory to raise the abstraction level and provide a better platform to build correct concurrent and scalable applications.

For fault-tolerance Akka adopts the "Let it crash", also called "Embrace failure", model which has been used with great success in the telecom industry to build applications that self-heal, systems that never stop.

Actors also provides the abstraction for transparent distribution and the basis for truly scalable and fault-tolerant applications.

Akka is Open Source and available under the Apache 2 License.

In this article we will introduce you to Akka and see how we can utilize it to build a highly concurrent, scalable and fault-tolerant network server.

But first let's take a step back and discuss what Actors really are and what they are useful for.

Actors
------

`The Actor Model <http://en.wikipedia.org/wiki/Actor_model>`_ provides a higher level of abstraction for writing concurrent and distributed systems. It alleviates the developer from having to deal with explicit locking and thread management. It makes it easier to write correct concurrent and parallel systems. Actors are really nothing new, they were defined in the 1963 paper by Carl Hewitt and have been popularized by the Erlang language which emerged in the mid 80s. It has been used by for example at Ericsson with great success to build highly concurrent and extremely reliable (99.9999999 % availability - 31 ms/year downtime) telecom systems.

Actors encapsulate state and behavior into a lightweight process/thread. In a sense they are like OO objects but with a major semantic difference; they *do not* share state with any other Actor. Each Actor has its own view of the world and can only have impact on other Actors by sending messages to them. Messages are sent asynchronously and non-blocking in a so-called "fire-and-forget" manner where the Actor sends off a message to some other Actor and then do not wait for a reply but goes off doing other things or are suspended by the runtime. Each Actor has a mailbox (ordered message queue) in which incoming messages are processed one by one. Since all processing is done asynchronously and Actors do not block and consume any resources while waiting for messages, Actors tend to give very good concurrency and scalability characteristics and are excellent for building event-based systems.

Creating Actors
---------------

Akka has both a Scala API (:ref:`actors-scala`) and a Java API (:ref:`untyped-actors-java`). In this article we will only look at the Scala API since that is the most expressive one. The article assumes some basic Scala knowledge, but even if you don't know Scala I don't think it will not be too hard to follow along anyway.

Akka has adopted the same style of writing Actors as Erlang in which each Actor has an explicit message handler which does pattern matching to match on the incoming messages.

Actors can be created either by:
* Extending the 'Actor' class and implementing the 'receive' method.
* Create an anonymous Actor using one of the 'actor' methods.

Here is a little example before we dive into a more interesting one.

.. code-block:: scala

  import akka.actor.Actor
  
  class MyActor extends Actor {
    def receive = {
      case "test" => println("received test")
      case _ =>      println("received unknown message")
    }
  }

  val myActor = Actor.actorOf[MyActor]
  myActor.start()

From this call we get a handle to the 'Actor' called 'ActorRef', which we can use to interact with the Actor

The 'actorOf' factory method can be imported like this:

.. code-block:: scala

  import akka.actor.Actor.actorOf

  val a = actorOf[MyActor]

From now on we will assume that it is imported like this and can use it directly.

Akka Actors are extremely lightweight. Each Actor consume ~600 bytes, which means that you can create 6.5 million on 4 GB RAM.

Messages are sent using the '!' operator:

.. code-block:: scala

  myActor ! "test"

Sample application
------------------

We will try to write a simple chat/IM system. It is client-server based and uses remote Actors to implement remote clients. Even if it is not likely that you will ever write a chat system I think that it can be a useful exercise since it uses patterns and idioms found in many other use-cases and domains.

We will use many of the features of Akka along the way. In particular; Actors, fault-tolerance using Actor supervision, remote Actors, Software Transactional Memory (STM) and persistence.

Creating an Akka SBT project
----------------------------

First we need to create an SBT project for our tutorial. You do that by stepping into the directory you want to create your project in and invoking the ``sbt`` command answering the questions for setting up your project::

    $ sbt
    Project does not exist, create new project? (y/N/s) y
    Name: Chat
    Organization: Hakkers Inc
    Version [1.0]:
    Scala version [2.9.1]:
    sbt version [0.7.6.RC0]:

Add the Akka SBT plugin definition to your SBT project by creating a ``Plugins.scala`` file in the ``project/plugins`` directory containing::

    import sbt._

    class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
      val akkaRepo   = "Akka Repo" at "http://akka.io/repository"
      val akkaPlugin = "se.scalablesolutions.akka" % "akka-sbt-plugin" % "1.3-RC7"
    }

Create a project definition ``project/build/Project.scala`` file containing::

    import sbt._

    class ChatProject(info: ProjectInfo) extends DefaultProject(info) with AkkaProject {
      val akkaRepo = "Akka Repo" at "http://akka.io/repository"
      val akkaSTM    = akkaModule("stm")
      val akkaRemote = akkaModule("remote")
    }


Make SBT download the dependencies it needs. That is done by invoking::

    > reload
    > update

From the SBT project you can generate files for your IDE:

- `SbtEclipsify <https://github.com/musk/SbtEclipsify>`_ to generate Eclipse project. Detailed instructions are available in :ref:`getting-started-first-scala-eclipse`.
- `sbt-idea <https://github.com/mpeltonen/sbt-idea>`_ to generate IntelliJ IDEA project.

Creating messages
-----------------

Let's start by defining the messages that will flow in our system. It is very important that all messages that will be sent around in the system are immutable. The Actor model relies on the simple fact that no state is shared between Actors and the only way to guarantee that is to make sure we don't pass mutable state around as part of the messages.

In Scala we have something called `case classes <http://www.scala-lang.org/node/107>`_. These make excellent messages since they are both immutable and great to pattern match on.

Let's now start by creating the messages that will flow in our system.

.. code-block:: scala

  sealed trait Event
  case class Login(user: String) extends Event
  case class Logout(user: String) extends Event
  case class GetChatLog(from: String) extends Event
  case class ChatLog(log: List[String]) extends Event
  case class ChatMessage(from: String, message: String) extends Event

As you can see with these messages we can log in and out, send a chat message and ask for and get a reply with all the messages in the chat log so far.

Client: Sending messages
------------------------

Our client wraps each message send in a function, making it a bit easier to use. Here we assume that we have a reference to the chat service so we can communicate with it by sending messages. Messages are sent with the '!' operator (pronounced "bang"). This sends a message of asynchronously and do not wait for a reply.

Sometimes however, there is a need for sequential logic, sending a message and
wait for the reply before doing anything else. In Akka we can achieve that
using the '?' operator. When sending a message with '?' we get back a `Future
<http://en.wikipedia.org/wiki/Futures_and_promises>`_. A 'Future' is a promise
that we will get a result later but with the difference from regular method
dispatch that the OS thread we are running on is put to sleep while waiting and
that we can set a time-out for how long we wait before bailing out, retrying or
doing something else. This waiting is achieved with the :meth:`Future.as[T]`
method, which returns a `scala.Option
<http://www.codecommit.com/blog/scala/the-option-pattern>`_ which implements
the `Null Object pattern <http://en.wikipedia.org/wiki/Null_Object_pattern>`_.
It has two subclasses; 'None' which means no result and 'Some(value)' which
means that we got a reply. The 'Option' class has a lot of great methods to
work with the case of not getting a defined result. F.e. as you can see below
we are using the 'getOrElse' method which will try to return the result and if
there is no result defined invoke the "...OrElse" statement.

.. code-block:: scala

  class ChatClient(val name: String) {
    val chat = Actor.remote.actorFor("chat:service", "localhost", 2552)

    def login                 = chat ! Login(name)
    def logout                = chat ! Logout(name)
    def post(message: String) = chat ! ChatMessage(name, name + ": " + message)
    def chatLog               = (chat ? GetChatLog(name)).as[ChatLog]
                                  .getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
  }

As you can see, we are using the 'Actor.remote.actorFor' to lookup the chat server on the remote node. From this call we will get a handle to the remote instance and can use it as it is local.

Session: Receiving messages
---------------------------

Now we are done with the client side and let's dig into the server code. We start by creating a user session. The session is an Actor and is defined by extending the 'Actor' trait. This trait has one abstract method that we have to define; 'receive' which implements the message handler for the Actor.

In our example the session has state in the form of a 'List' with all the messages sent by the user during the session. In takes two parameters in its constructor; the user name and a reference to an Actor implementing the persistent message storage. For both of the messages it responds to, 'ChatMessage' and 'GetChatLog', it passes them on to the storage Actor.

If you look closely (in the code below) you will see that when passing on the 'GetChatLog' message we are not using '!' but 'forward'. This is similar to '!' but with the important difference that it passes the original sender reference, in this case to the storage Actor. This means that the storage can use this reference to reply to the original sender (our client) directly.

.. code-block:: scala

  class Session(user: String, storage: ActorRef) extends Actor {
    private val loginTime = System.currentTimeMillis
    private var userLog: List[String] = Nil

    EventHandler.info(this, "New session for user [%s] has been created at [%s]".format(user, loginTime))

    def receive = {
      case msg @ ChatMessage(from, message) =>
        userLog ::= message
        storage ! msg

      case msg @ GetChatLog(_) =>
        storage forward msg
    }
  }

Let it crash: Implementing fault-tolerance
------------------------------------------

Akka's `approach to fault-tolerance <fault-tolerance>`_; the "let it crash" model, is implemented by linking Actors. It is very different to what Java and most non-concurrency oriented languages/frameworks have adopted. It’s a way of dealing with failure that is designed for concurrent and distributed systems.

If we look at concurrency first. Now let’s assume we are using non-linked Actors. Throwing an exception in concurrent code, will just simply blow up the thread that currently executes the Actor. There is no way to find out that things went wrong (apart from see the stack trace in the log). There is nothing you can do about it. Here linked Actors provide a clean way of both getting notification of the error so you know what happened, as well as the Actor that crashed, so you can do something about it.

Linking Actors allow you to create sets of Actors where you can be sure that either:

* All are dead
* All are alive

This is very useful when you have hundreds of thousands of concurrent Actors. Some Actors might have implicit dependencies and together implement a service, computation, user session etc. for these being able to group them is very nice.

Akka encourages non-defensive programming. Don’t try to prevent things from go wrong, because they will, whether you want it or not. Instead; expect failure as a natural state in the life-cycle of your app, crash early and let someone else (that sees the whole picture), deal with it.

Now let’s look at distributed Actors. As you probably know, you can’t build a fault-tolerant system with just one single node, but you need at least two. Also, you (usually) need to know if one node is down and/or the service you are talking to on the other node is down. Here Actor supervision/linking is a critical tool for not only monitoring the health of remote services, but to actually manage the service, do something about the problem if the Actor or node is down. This could be restarting him on the same node or on another node.

To sum things up, it is a very different way of thinking but a way that is very useful (if not critical) to building fault-tolerant highly concurrent and distributed applications.

Supervisor hierarchies
----------------------

A supervisor is a regular Actor that is responsible for starting, stopping and monitoring its child Actors. The basic idea of a supervisor is that it should keep its child Actors alive by restarting them when necessary. This makes for a completely different view on how to write fault-tolerant servers. Instead of trying all things possible to prevent an error from happening, this approach embraces failure. It shifts the view to look at errors as something natural and something that will happen and instead of trying to prevent it; embrace it. Just "let it crash" and reset the service to a stable state through restart.

Akka has two different restart strategies; All-For-One and One-For-One.

* OneForOne: Restart only the component that has crashed.
* AllForOne: Restart all the components that the supervisor is managing, including the one that have crashed.

The latter strategy should be used when you have a certain set of components that are coupled in some way that if one is crashing they all need to be reset to a stable state before continuing.

Chat server: Supervision, Traits and more
-----------------------------------------

There are two ways you can define an Actor to be a supervisor; declaratively and dynamically. In this example we use the dynamic approach. There are two things we have to do:

* Define the fault handler by setting the 'faultHandler' member field to the strategy we want.
* Define the exceptions we want to "trap", e.g. which exceptions should be handled according to the fault handling strategy we have defined. This in done by setting the 'trapExit' member field to a 'List' with all exceptions we want to trap.

The last thing we have to do to supervise Actors (in our example the storage Actor) is to 'link' the Actor. Invoking 'link(actor)' will create a link between the Actor passed as argument into 'link' and ourselves. This means that we will now get a notification if the linked Actor is crashing and if the cause of the crash, the exception, matches one of the exceptions in our 'trapExit' list then the crashed Actor is restarted according the the fault handling strategy defined in our 'faultHandler'. We also have the 'unlink(actor)' function which disconnects the linked Actor from the supervisor.

In our example we are using a method called 'spawnLink(actor)' which creates, starts and links the Actor in an atomic operation. The linking and unlinking is done in 'preStart' and 'postStop' callback methods which are invoked by the runtime when the Actor is started and shut down (shutting down is done by invoking 'actor.stop()'). In these methods we initialize our Actor, by starting and linking the storage Actor and clean up after ourselves by shutting down all the user session Actors and the storage Actor.

That is it. Now we have implemented the supervising part of the fault-tolerance for the storage Actor. But before we dive into the 'ChatServer' code there are some more things worth mentioning about its implementation.

It defines an abstract member field holding the 'ChatStorage' implementation the server wants to use. We do not define that in the 'ChatServer' directly since we want to decouple it from the actual storage implementation.

The 'ChatServer' is a 'trait', which is Scala's version of mixins. A mixin can be seen as an interface with an implementation and is a very powerful tool in Object-Oriented design that makes it possible to design the system into small, reusable, highly cohesive, loosely coupled parts that can be composed into larger object and components structures.

I'll try to show you how we can make use Scala's mixins to decouple the Actor implementation from the business logic of managing the user sessions, routing the chat messages and storing them in the persistent storage. Each of these separate parts of the server logic will be represented by its own trait; giving us four different isolated mixins; 'Actor', 'SessionManagement', 'ChatManagement' and 'ChatStorageFactory' This will give us as loosely coupled system with high cohesion and reusability. At the end of the article I'll show you how you can compose these mixins into a the complete runtime component we like.

.. code-block:: scala

  /**
   * Chat server. Manages sessions and redirects all other messages to the Session for the client.
   */
  trait ChatServer extends Actor {
    self.faultHandler = OneForOneStrategy(List(classOf[Exception]),5, 5000)
    val storage: ActorRef

    EventHandler.info(this, "Chat server is starting up...")

    // actor message handler
    def receive: Receive = sessionManagement orElse chatManagement

    // abstract methods to be defined somewhere else
    protected def chatManagement: Receive
    protected def sessionManagement: Receive
    protected def shutdownSessions(): Unit

    override def postStop() = {
      EventHandler.info(this, "Chat server is shutting down...")
      shutdownSessions
      self.unlink(storage)
      storage.stop()
    }
  }

If you look at the 'receive' message handler function you can see that we have defined it but instead of adding our logic there we are delegating to two different functions; 'sessionManagement' and 'chatManagement', chaining them with 'orElse'. These two functions are defined as abstract in our 'ChatServer' which means that they have to be provided by some another mixin or class when we instantiate our 'ChatServer'. Naturally we will put the 'sessionManagement' implementation in the 'SessionManagement' trait and the 'chatManagement' implementation in the 'ChatManagement' trait. First let's create the 'SessionManagement' trait.

Chaining partial functions like this is a great way of composing functionality in Actors. You can for example put define one default message handle handling generic messages in the base Actor and then let deriving Actors extend that functionality by defining additional message handlers. There is a section on how that is done `here <actors>`_.

Session management
------------------

The session management is defined in the 'SessionManagement' trait in which we implement the two abstract methods in the 'ChatServer'; 'sessionManagement' and 'shutdownSessions'.

The 'SessionManagement' trait holds a 'HashMap' with all the session Actors mapped by user name as well as a reference to the storage (to be able to pass it in to each newly created 'Session').

The 'sessionManagement' function performs session management by responding to the 'Login' and 'Logout' messages. For each 'Login' message it creates a new 'Session' Actor, starts it and puts it in the 'sessions' Map and for each 'Logout' message it does the opposite; shuts down the user's session and removes it from the 'sessions' Map.

The 'shutdownSessions' function simply shuts all the sessions Actors down. That completes the user session management.

.. code-block:: scala

  /**
   * Implements user session management.
   * <p/>
   * Uses self-type annotation (this: Actor =>) to declare that it needs to be mixed in with an Actor.
   */
  trait SessionManagement { this: Actor =>

    val storage: ActorRef // needs someone to provide the ChatStorage
    val sessions = new HashMap[String, ActorRef]

    protected def sessionManagement: Receive = {
      case Login(username) =>
        EventHandler.info(this, "User [%s] has logged in".format(username))
        val session = actorOf(new Session(username, storage))
        session.start()
        sessions += (username -> session)

      case Logout(username) =>
        EventHandler.info(this, "User [%s] has logged out".format(username))
        val session = sessions(username)
        session.stop()
        sessions -= username
    }

    protected def shutdownSessions =
      sessions.foreach { case (_, session) => session.stop() }
  }

Chat message management
-----------------------

Chat message management is implemented by the 'ChatManagement' trait. It has an abstract 'HashMap' session member field with all the sessions. Since it is abstract it needs to be mixed in with someone that can provide this reference. If this dependency is not resolved when composing the final component, you will get a compilation error.

It implements the 'chatManagement' function which responds to two different messages; 'ChatMessage' and 'GetChatLog'. It simply gets the session for the user (the sender of the message) and routes the message to this session. Here we also use the 'forward' function to make sure the original sender reference is passed along to allow the end receiver to reply back directly.

.. code-block:: scala

  /**
   * Implements chat management, e.g. chat message dispatch.
   * <p/>
   * Uses self-type annotation (this: Actor =>) to declare that it needs to be mixed in with an Actor.
   */
  trait ChatManagement { this: Actor =>
    val sessions: HashMap[String, ActorRef] // needs someone to provide the Session map

    protected def chatManagement: Receive = {
      case msg @ ChatMessage(from, _) => getSession(from).foreach(_ ! msg)
      case msg @ GetChatLog(from) =>     getSession(from).foreach(_ forward msg)
    }

    private def getSession(from: String) : Option[ActorRef] = {
      if (sessions.contains(from))
        Some(sessions(from))
      else {
        EventHandler.info(this, "Session expired for %s".format(from))
        None
      }
    }
  }

Using an Actor as a message broker, as in this example, is a very common pattern with many variations; load-balancing, master/worker, map/reduce, replication, logging etc. It becomes even more useful with remote Actors when we can use it to route messages to different nodes.

STM and Transactors
-------------------

Actors are excellent for solving problems where you have many independent processes that can work in isolation and only interact with other Actors through message passing. This model fits many problems. But the Actor model is unfortunately a terrible model for implementing truly shared state. E.g. when you need to have consensus and a stable view of state across many components. The classic example is the bank account where clients can deposit and withdraw, in which each operation needs to be atomic. For detailed discussion on the topic see this `presentation <http://www.slideshare.net/jboner/state-youre-doing-it-wrong-javaone-2009>`_.

`Software Transactional Memory <http://en.wikipedia.org/wiki/Software_transactional_memory>`_ (STM) on the other hand is excellent for problems where you need consensus and a stable view of the state by providing compositional transactional shared state. Some of the really nice traits of STM are that transactions compose and that it raises the abstraction level from lock-based concurrency.

Akka has a `STM implementation <stm>`_ that is based on the same ideas as found in the `Clojure language <http://clojure.org/>`_; Managed References working with immutable data.

Akka allows you to combine Actors and STM into what we call `Transactors <transactors>`_ (short for Transactional Actors), these allow you to optionally combine Actors and STM provides IMHO the best of the Actor model (simple concurrency and asynchronous event-based programming) and STM (compositional transactional shared state) by providing transactional, compositional, asynchronous, event-based message flows. You don't need Transactors all the time but when you do need them then you *really need* them.

Akka currently provides three different transactional abstractions; 'Map', 'Vector' and 'Ref'. They can be shared between multiple Actors and they are managed by the STM. You are not allowed to modify them outside a transaction, if you do so, an exception will be thrown.

What you get is transactional memory in which multiple Actors are allowed to read and write to the same memory concurrently and if there is a clash between two transactions then both of them are aborted and retried. Aborting a transaction means that the memory is rolled back to the state it were in when the transaction was started.

In database terms STM gives you 'ACI' semantics; 'Atomicity', 'Consistency' and 'Isolation'. The 'D' in 'ACID'; 'Durability', you can't get with an STM since it is in memory.
It possible to implement durable persistence for the transactional data structures, but in this sample we keep them in memory.

Chat storage: Backed with simple in-memory
------------------------------------------

To keep it simple we implement the persistent storage, with a in-memory Vector, i.e. it will not be persistent. We start by creating a 'ChatStorage' trait allowing us to have multiple different storage backend. For example one in-memory and one persistent.

.. code-block:: scala

  /**
   * Abstraction of chat storage holding the chat log.
   */
  trait ChatStorage extends Actor

Our 'MemoryChatStorage' extends the 'ChatStorage' trait. The only state it holds is the 'chatLog' which is a transactional 'Vector'.

It responds to two different messages; 'ChatMessage' and 'GetChatLog'. The 'ChatMessage' message handler takes the 'message' attribute and appends it to the 'chatLog' vector. Here you can see that we are using the 'atomic { ... }' block to run the vector operation in a transaction. For this in-memory storage it is not important to use a transactional Vector, since it is not shared between actors, but it illustrates the concept.

The 'GetChatLog' message handler retrieves all the messages in the chat log storage inside an atomic block, iterates over them using the 'map' combinator transforming them from 'Array[Byte] to 'String'. Then it invokes the 'reply(message)' function that will send the chat log to the original sender; the 'ChatClient'.

You might remember that the 'ChatServer' was supervising the 'ChatStorage' actor. When we discussed that we showed you the supervising Actor's view. Now is the time for the supervised Actor's side of things. First, a supervised Actor need to define a life-cycle in which it declares if it should be seen as a:

* 'Permanent': which means that the actor will always be restarted.
* 'Temporary': which means that the actor will not be restarted, but it will be shut down through the regular shutdown process so the 'postStop' callback function will called.

We define the 'MemoryChatStorage' as 'Permanent' by setting the 'lifeCycle' member field to 'Permanent'.

The idea with this crash early style of designing your system is that the services should just crash and then they should be restarted and reset into a stable state and continue from there. The definition of "stable state" is domain specific and up to the application developer to define. Akka provides two callback functions; 'preRestart' and 'postRestart' that are called right *before* and right *after* the Actor is restarted. Both of these functions take a 'Throwable', the reason for the crash, as argument. In our case we just need to implement the 'postRestart' hook and there re-initialize the 'chatLog' member field with a fresh 'Vector'.

.. code-block:: scala

  /**
   * Memory-backed chat storage implementation.
   */
  class MemoryChatStorage extends ChatStorage {
    self.lifeCycle = Permanent

    private var chatLog = TransactionalVector[Array[Byte]]()

    EventHandler.info(this, "Memory-based chat storage is starting up...")

    def receive = {
      case msg @ ChatMessage(from, message) =>
        EventHandler.debug(this, "New chat message [%s]".format(message))
        atomic { chatLog + message.getBytes("UTF-8") }

      case GetChatLog(_) =>
        val messageList = atomic { chatLog.map(bytes => new String(bytes, "UTF-8")).toList }
        self.reply(ChatLog(messageList))
    }

    override def postRestart(reason: Throwable) = chatLog = TransactionalVector()
  }

The last thing we need to do in terms of persistence is to create a 'MemoryChatStorageFactory' that will take care of instantiating and resolving the 'val storage: ChatStorage' field in the 'ChatServer' with a concrete implementation of our persistence Actor.

.. code-block:: scala

  /**
   * Creates and links a MemoryChatStorage.
   */
  trait MemoryChatStorageFactory { this: Actor =>
    val storage = this.self.spawnLink[MemoryChatStorage] // starts and links ChatStorage
  }

Composing the full Chat Service
-------------------------------

We have now created the full functionality for the chat server, all nicely decoupled into isolated and well-defined traits. Now let's bring all these traits together and compose the complete concrete 'ChatService'.

.. code-block:: scala

  /**
   * Class encapsulating the full Chat Service.
   * Start service by invoking:
   * <pre>
   * val chatService = Actor.actorOf[ChatService].start()
   * </pre>
   */
  class ChatService extends
    ChatServer with
    SessionManagement with
    ChatManagement with
    MemoryChatStorageFactory {
    override def preStart() = {
      remote.start("localhost", 2552);
      remote.register("chat:service", self) //Register the actor with the specified service id
    }
  }

Creating a remote server service
--------------------------------

As you can see in the section above, we are overriding the Actor's 'start' method and are starting up a remote server node by invoking 'remote.start("localhost", 2552)'. This starts up the remote node on address "localhost" and port 2552 which means that it accepts incoming messages on this address. Then we register the ChatService actor in the remote node by invoking 'remote.register("chat:service", self)'. This means that the ChatService will be available to other actors on this specific id, address and port.

That's it. Were done. Now we have a, very simple, but scalable, fault-tolerant, event-driven, persistent chat server that can without problem serve a million concurrent users on a regular workstation.

Let's use it.

Sample client chat session
--------------------------

Now let's create a simple test runner that logs in posts some messages and logs out.

.. code-block:: scala

  /**
   * Test runner emulating a chat session.
   */
  object ClientRunner {

    def run = {
      val client1 = new ChatClient("jonas")
      client1.login
      val client2 = new ChatClient("patrik")
      client2.login

      client1.post("Hi there")
      println("CHAT LOG:\n\t" + client1.chatLog.log.mkString("\n\t"))

      client2.post("Hello")
      println("CHAT LOG:\n\t" + client2.chatLog.log.mkString("\n\t"))

      client1.post("Hi again")
      println("CHAT LOG:\n\t" + client1.chatLog.log.mkString("\n\t"))

      client1.logout
      client2.logout
    }
  }

Sample code
-----------

All this code is available as part of the Akka distribution. It resides in the './akka-samples/akka-sample-chat' module and have a 'README' file explaining how to run it.

Or if you rather browse it `online <https://github.com/jboner/akka/blob/master/akka-samples/akka-sample-chat/>`_.

Run it
------

Download and build Akka

#. Check out Akka from `<http://github.com/jboner/akka>`_
#. Set 'AKKA_HOME' environment variable to the root of the Akka distribution.
#. Open up a shell and step into the Akka distribution root folder.
#. Build Akka by invoking:

::

  % sbt update
  % sbt dist

Run a sample chat session

1. Fire up two shells. For each of them:

  - Step down into to the root of the Akka distribution.
  - Set 'export AKKA_HOME=<root of distribution>.
  - Run 'sbt console' to start up a REPL (interpreter).

2. In the first REPL you get execute:

.. code-block:: scala

  import sample.chat._
  import akka.actor.Actor._
  val chatService = actorOf[ChatService].start()

3. In the second REPL you get execute:

.. code-block:: scala

  import sample.chat._
  ClientRunner.run

4. See the chat simulation run.

5. Run it again to see full speed after first initialization.

6. In the client REPL, or in a new REPL, you can also create your own client

.. code-block:: scala

  import sample.chat._
  val myClient = new ChatClient("<your name>")
  myClient.login
  myClient.post("Can I join?")
  println("CHAT LOG:\n\t" + myClient.chatLog.log.mkString("\n\t"))

That's it. Have fun.
