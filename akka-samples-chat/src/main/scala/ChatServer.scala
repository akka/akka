/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
 */

package se.scalablesolutions.akka.sample.chat

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor, RemoteActor}
import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{OneForOneStrategy}

/******************************************************************************
  To run the sample: 
  1. Run 'mvn install' (builds and deploys jar to AKKA_HOME/deploy)
  2. In another shell run 'java -jar ./dist/akka-0.6.jar' to start up Akka microkernel
  3. In the first shell run 'mvn scala:console -o'
  4. In the REPL you get execute: 
    - scala> import se.scalablesolutions.akka.sample.chat._
    - scala> Runner.run
  5. See the chat simulation run
  6. Run it again if you like
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
  def login =                 ChatServer ! Login(name) 
  def logout =                ChatServer ! Logout(name)  
  def post(message: String) = ChatServer ! ChatMessage(name, name + ": " + message)  
  def chatLog: ChatLog =     (ChatServer !! GetChatLog(name)).getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
}

/**
 * Internal chat client session.
 */
class Session(user: String, storage: Actor) extends Actor {
  lifeCycle = Some(LifeCycle(Permanent))        
  private val loginTime = System.currentTimeMillis
  private var userLog: List[String] = Nil
  
  log.info("New session for user [%s] has been created", user)

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
 * Chat server. Manages sessions and redirects all other messages to the Session for the client.
 */
object ChatServer extends Actor {
  id = "ChatServer"
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = List(classOf[Exception])
  
  private var storage: Storage = _
  private var sessions = Map[String, Actor]()

  log.info("Chat service is starting up...")

  override def init = storage = spawnLink(classOf[Storage])
  
  def receive = sessionManagement orElse chatting
  
  private def sessionManagement: PartialFunction[Any, Unit] = {
    case Login(username) => 
      log.info("User [%s] has logged in", username)
      val session = new Session(username, storage)
      startLink(session)
      sessions = sessions + (username -> session)
      
    case Logout(username) =>        
      log.info("User [%s] has logged out", username)
      val session = sessions(username)
      unlink(session)
      session.stop
      sessions = sessions - username 
  }  
  
  private def chatting: PartialFunction[Any, Unit] = {
    case msg @ ChatMessage(from, _) => sessions(from) ! msg
    case msg @ GetChatLog(from) =>     sessions(from) forward msg
  }
  
  override def shutdown = { 
    sessions.foreach { case (_, session) => 
      log.info("Chat server is shutting down...")
      unlink(session)
      session.stop
    }
    unlink(storage)
    storage.stop
  }
}

class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
      Supervise(
        ChatServer,
        LifeCycle(Permanent)) 
      :: Nil))
  factory.newInstance.start
}

object Runner {
  ChatServer.makeRemote("localhost", 9999)
  ChatServer.start
  
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