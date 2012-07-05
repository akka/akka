  /**
   * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
   */

  package sample.chat

  import scala.collection.mutable.HashMap

  import akka.actor.{Actor, ActorRef}
  import akka.stm._
  import akka.config.Supervision.{OneForOneStrategy,Permanent}
  import Actor._
  import akka.event.EventHandler

  /******************************************************************************
  Akka Chat Client/Server Sample Application
  
  How to run the sample:

  1. Fire up two shells. For each of them:
    - Step down into to the root of the Akka distribution.
    - Set 'export AKKA_HOME=<root of distribution>.
    - Run 'sbt console' to start up a REPL (interpreter).
  2. In the first REPL you get execute:
    - scala> import sample.chat._
    - scala> import akka.actor.Actor._
    - scala> val chatService = actorOf[ChatService].start()
  3. In the second REPL you get execute:
      - scala> import sample.chat._
      - scala> ClientRunner.run
  4. See the chat simulation run.
  5. Run it again to see full speed after first initialization.
  6. In the client REPL, or in a new REPL, you can also create your own client
   - scala> import sample.chat._
   - scala> val myClient = new ChatClient("<your name>")
   - scala> myClient.login
   - scala> myClient.post("Can I join?")
   - scala> println("CHAT LOG:\n\t" + myClient.chatLog.log.mkString("\n\t"))


  That’s it. Have fun.

  ******************************************************************************/

  /**
   * ChatServer's internal events.
   */
  sealed trait Event
  case class Login(user: String) extends Event
  case class Logout(user: String) extends Event
  case class GetChatLog(from: String) extends Event
  case class ChatLog(log: List[String]) extends Event
  case class ChatMessage(from: String, message: String) extends Event

  /**
   * Chat client.
   */
  class ChatClient(val name: String) {
    val chat = Actor.remote.actorFor("chat:service", "localhost", 2552)

    def login                 = chat ! Login(name)
    def logout                = chat ! Logout(name)
    def post(message: String) = chat ! ChatMessage(name, name + ": " + message)
    def chatLog               = (chat !! GetChatLog(name)).as[ChatLog].getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
  }

  /**
   * Internal chat client session.
   */
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

  /**
   * Abstraction of chat storage holding the chat log.
   */
  trait ChatStorage extends Actor

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

    override def postRestart(reason: Throwable) {
      chatLog = TransactionalVector()
    }
  }

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

    protected def shutdownSessions() {
      sessions.foreach { case (_, session) => session.stop() }
    }
  }

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

  /**
   * Creates and links a MemoryChatStorage.
   */
  trait MemoryChatStorageFactory { this: Actor =>
    val storage = Actor.actorOf[MemoryChatStorage]
    this.self.startLink(storage) // starts and links ChatStorage
  }

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
    protected def shutdownSessions()

    override def postStop() {
      EventHandler.info(this, "Chat server is shutting down...")
      shutdownSessions()
      self.unlink(storage)
      storage.stop()
    }
  }

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
    override def preStart() {
      remote.start("localhost", 2552);
      remote.register("chat:service", self) //Register the actor with the specified service id
    }
  }

  /**
   * Test runner starting ChatService.
   */
  object ServerRunner {

    def main(args: Array[String]) { ServerRunner.run() }

    def run() {
      actorOf[ChatService].start()
    }
  }

  /**
   * Test runner emulating a chat session.
   */
  object ClientRunner {

    def main(args: Array[String]) { ClientRunner.run() }

    def run() {

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

