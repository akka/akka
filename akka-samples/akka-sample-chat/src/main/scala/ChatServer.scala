/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
 */

package sample.chat

import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor, ActorRef, RemoteActor}
import se.scalablesolutions.akka.remote.{RemoteNode, RemoteClient}
import se.scalablesolutions.akka.persistence.common.PersistentVector
import se.scalablesolutions.akka.persistence.redis.RedisStorage
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.OneForOneStrategy
import se.scalablesolutions.akka.util.Logging
import Actor._

/******************************************************************************
Akka Chat Client/Server Sample Application

First we need to download, build and start up Redis:

1. Download Redis from http://code.google.com/p/redis/downloads/list.
2. Step into the distribution.
3. Build: ‘make install’.
4. Run: ‘./redis-server’.
For details on how to set up Redis server have a look at http://code.google.com/p/redis/wiki/QuickStart.

Then to run the sample:

1. Fire up two shells. For each of them:
  - Step down into to the root of the Akka distribution.
  - Set 'export AKKA_HOME=<root of distribution>.
  - Run 'sbt console' to start up a REPL (interpreter).
2. In the first REPL you get execute:
  - scala> import sample.chat._
  - scala> import se.scalablesolutions.akka.actor.Actor._
  - scala> val chatService = actorOf[ChatService].start
3. In the second REPL you get execute:
    - scala> import sample.chat._
    - scala> Runner.run
4. See the chat simulation run.
5. Run it again to see full speed after first initialization.

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
  val chat = RemoteClient.actorFor("chat:service", "localhost", 9999)

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
 * Abstraction of chat storage holding the chat log.
 */
trait ChatStorage extends Actor

/**
 * Redis-backed chat storage implementation.
 */
class RedisChatStorage extends ChatStorage {
  self.lifeCycle = Some(LifeCycle(Permanent))
  val CHAT_LOG = "akka.chat.log"

  private var chatLog = atomic { RedisStorage.getVector(CHAT_LOG) }

  log.info("Redis-based chat storage is starting up...")

  def receive = {
    case msg @ ChatMessage(from, message) =>
      log.debug("New chat message [%s]", message)
      atomic { chatLog + message.getBytes("UTF-8") }

    case GetChatLog(_) =>
      val messageList = atomic { chatLog.map(bytes => new String(bytes, "UTF-8")).toList }
      self.reply(ChatLog(messageList))
  }

  override def postRestart(reason: Throwable) = chatLog = RedisStorage.getVector(CHAT_LOG)
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
      log.info("User [%s] has logged in", username)
      val session = actorOf(new Session(username, storage))
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
  val sessions: HashMap[String, ActorRef] // needs someone to provide the Session map

  protected def chatManagement: Receive = {
    case msg @ ChatMessage(from, _) => sessions(from) ! msg
    case msg @ GetChatLog(from) =>     sessions(from) forward msg
  }
}

/**
 * Creates and links a RedisChatStorage.
 */
trait RedisChatStorageFactory { this: Actor =>
  val storage = this.self.spawnLink[RedisChatStorage] // starts and links ChatStorage
}

/**
 * Chat server. Manages sessions and redirects all other messages to the Session for the client.
 */
trait ChatServer extends Actor {
  self.faultHandler = Some(OneForOneStrategy(5, 5000))
  self.trapExit = List(classOf[Exception])

  val storage: ActorRef

  log.info("Chat server is starting up...")

  // actor message handler
  def receive = sessionManagement orElse chatManagement

  // abstract methods to be defined somewhere else
  protected def chatManagement: Receive
  protected def sessionManagement: Receive
  protected def shutdownSessions(): Unit

  override def shutdown = {
    log.info("Chat server is shutting down...")
    shutdownSessions
    self.unlink(storage)
    storage.stop
  }
}

/**
 * Class encapsulating the full Chat Service.
 * Start service by invoking:
 * <pre>
 * val chatService = Actor.actorOf[ChatService].start
 * </pre>
 */
class ChatService extends
  ChatServer with
  SessionManagement with
  ChatManagement with
  RedisChatStorageFactory {
  override def init = {
    RemoteNode.start("localhost", 9999)
    RemoteNode.register("chat:service", self)
  }
}

/**
 * Test runner emulating a chat session.
 */
object Runner {
  def run = {
    val client = new ChatClient("jonas")

    client.login

    client.post("Hi there")
    println("CHAT LOG:\n\t" + client.chatLog.log.mkString("\n\t"))

    client.post("Hi again")
    println("CHAT LOG:\n\t" + client.chatLog.log.mkString("\n\t"))

    client.logout
  }
}
