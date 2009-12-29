/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
 */

package se.scalablesolutions.akka.sample.chat

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor, RemoteActor}
import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{OneForOneStrategy}

import scala.collection.mutable.HashMap

/******************************************************************************
  To run the sample: 
  1. Run 'mvn install' (builds and deploys jar to AKKA_HOME/deploy)
  2. In another shell run 'java -jar ./dist/akka-0.6.jar' to start up Akka microkernel
  3. In the first shell run 'mvn scala:console -o'
  4. In the REPL you get execute: 
    - scala> import se.scalablesolutions.akka.sample.chat._
    - scala> Runner.run
  5. See the chat simulation run
  6. Run it again to see full speed after first initialization
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
  import Actor.Sender.Self
  def login =                 ChatService ! Login(name) 
  def logout =                ChatService ! Logout(name)  
  def post(message: String) = ChatService ! ChatMessage(name, name + ": " + message)  
  def chatLog: ChatLog =     (ChatService !! GetChatLog(name)).getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
}

/**
 * Internal chat client session.
 */
class Session(user: String, storage: Actor) extends Actor {
  private val loginTime = System.currentTimeMillis
  private var userLog: List[String] = Nil
  
  log.info("New session for user [%s] has been created at [%s]", user, loginTime)

  def receive = {
    case msg @ ChatMessage(from, message) => 
      userLog ::= message
      storage ! msg
      
    case msg @ GetChatLog(_) => 
      storage forward msg
  }
}

/**
 * Chat storage holding the chat log.
 */
class Storage extends Actor {
  lifeCycle = Some(LifeCycle(Permanent))        
  private var chatLog: List[String] = Nil

  log.info("Chat storage is starting up...")

  def receive = {
    case msg @ ChatMessage(from, message) => 
      log.debug("New chat message [%s]", message)
      chatLog ::= message

    case GetChatLog(_) => 
      reply(ChatLog(chatLog.reverse))
  }
}

/**
 * Implements user session management.
 * <p/>
 * Uses self-type annotation (this: Actor =>) to declare that it needs to be mixed in with an Actor.
 */
trait SessionManagement { this: Actor => 
  
  val storage: Storage // needs someone to provide the Storage
  val sessions = new HashMap[String, Actor]
  
  protected def sessionManagement: PartialFunction[Any, Unit] = {
    case Login(username) => 
      log.info("User [%s] has logged in", username)
      val session = new Session(username, storage)
      session.start
      sessions += (username -> session)
      
    case Logout(username) =>        
      log.info("User [%s] has logged out", username)
      val session = sessions(username)
      session.stop
      sessions -= username 
  }  
  
  protected def shutdownSessions = 
    sessions.foreach { case (_, session) => session.stop }  
}

/**
 * Implements chat management, e.g. chat message dispatch.
 * <p/>
 * Uses self-type annotation (this: Actor =>) to declare that it needs to be mixed in with an Actor.
 */
trait ChatManagement { this: Actor =>
  val sessions: HashMap[String, Actor] // needs someone to provide the Session map
  
  protected def chatManagement: PartialFunction[Any, Unit] = {
    case msg @ ChatMessage(from, _) => sessions(from) ! msg
    case msg @ GetChatLog(from) =>     sessions(from) forward msg
  }
}

/**
 * Chat server. Manages sessions and redirects all other messages to the Session for the client.
 */
trait ChatServer extends Actor {
  id = "ChatServer" // setting ID to make sure there is only one single ChatServer on the remote node
  
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = List(classOf[Exception])
  
  val storage: Storage = spawnLink(classOf[Storage]) // starts and links Storage

  log.info("Chat service is starting up...")

  // actor message handler
  def receive = sessionManagement orElse chatManagement
  
  // abstract methods to be defined somewhere else
  protected def chatManagement: PartialFunction[Any, Unit]
  protected def sessionManagement: PartialFunction[Any, Unit]   
  protected def shutdownSessions: Unit

  override def shutdown = { 
    log.info("Chat server is shutting down...")
    shutdownSessions
    unlink(storage)
    storage.stop
  }
}

/**
 * Object encapsulating the full Chat Service.
 */
object ChatService extends ChatServer with SessionManagement with ChatManagement

/**
 * Boot class for running the ChatService in the Akka microkernel.
 * <p/>
 * Configures supervision of the ChatService for fault-tolerance.
 */
class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
      Supervise(
        ChatService,
        LifeCycle(Permanent),
        RemoteAddress("localhost", 9999)) 
      :: Nil))
  factory.newInstance.start
}

/**
 * Test runner emulating a chat session.
 */
object Runner {
  
  // create a handle to the remote ChatService 
  ChatService.makeRemote("localhost", 9999)
  ChatService.start
  
  def run = {
    val client = new ChatClient("jonas")
  
    client.login

    client.post("Hi there")
    println("CHAT LOG: " + client.chatLog.log)

    client.post("Hi again")
    println("CHAT LOG: " + client.chatLog.log)

    client.logout
  }
}