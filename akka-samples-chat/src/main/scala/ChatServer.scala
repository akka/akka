/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>.
 */

package se.scalablesolutions.akka.sample.chat

import se.scalablesolutions.akka.actor.{SupervisorFactory, Actor, RemoteActor}
import se.scalablesolutions.akka.remote.RemoteServer
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{OneForOneStrategy}

/**
 * ChatServer's internal messages.
 */
sealed trait Event
case class Login(user: String) extends Event
case class Logout(user: String) extends Event
case class GetChatLog(from: String) extends Event
case class ChatLog(log: List[String]) extends Event
case class ChatMessage(from: String, message: String) extends Event

/**
 * <pre>
 * curl http://localhost:9998/chat
 * </pre>
 * Or browse to the URL from a web browser.
 */
//@Path("/chat")
object ChatServer extends Actor {
  faultHandler = Some(OneForOneStrategy(5, 5000))
  trapExit = List(classOf[Exception])

  log.info("Chat server is starting up...")

  private var sessions = Map[String, Actor]()
  private var chatLog: List[String] = Nil

  class Session(user: String) extends Actor {
    lifeCycle = Some(LifeCycle(Permanent))        
    private val loginTime = System.currentTimeMillis
    private var userLog: List[String] = Nil
    
    log.info("New session for user [%s] has been created", user)

    def receive = {
      case ChatMessage(from, message) => userLog ::= message        
      case chatLog @ ChatLog(_) => reply(chatLog)
    }
  }

  def receive = chatting orElse sessionManagement
  
  private def sessionManagement: PartialFunction[Any, Unit] = {
    case Login(user) => 
      log.info("User [%s] has logged in", user)
      val session = new Session(user)
      startLink(session)
      sessions = sessions + (user -> session)
      
    case Logout(user) =>        
      log.info("User [%s] has logged out", user)
      val session = sessions(user)
      unlink(session)
      session.stop
      sessions = sessions - user 
  }  
  
  private def chatting: PartialFunction[Any, Unit] = {
    case msg @ ChatMessage(from, message) => 
      log.debug("New chat message [%s]", message)
      chatLog ::= message
      sessions(from).forward(msg)

    case GetChatLog(from) => //reply(ChatLog(chatLog.reverse))
      sessions(from).forward(ChatLog(chatLog.reverse))
  }
  
  override def shutdown = sessions.foreach { case (user, session) => 
    log.info("Chat server is shutting down...")
    unlink(session)
    session.stop
  }
}

class User extends Actor { me: User => 
  private var name: String = "unknown"

  def login(n: String) = { 
    name = n
    ChatServer ! Login(name) 
  }

  def logout =  ChatServer ! Logout(name)  

  def chatLog: List[String] = {
    val chatLog: ChatLog = (ChatServer !! GetChatLog(name)).getOrElse(throw new Exception("Couldn't get the chat log from ChatServer"))
    chatLog.log
  }
  
  def post(message: String) = ChatServer ! ChatMessage(name, name + ": " + message)  
  
  def receive = { 
    case _ => log.error("User does not respond to messages") 
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

object ChatBot {
//  val server = new RemoteServer
//  server.start("localhost", 1999)
  
  def run = {
    ChatServer.makeRemote("localhost", 9999)
    ChatServer.start
    
    val user = new User
  
    user.start
    user.login("jonas")

    user.post("Hi there")
    println("CHAT LOG: " + user.chatLog)

    user.post("Hi again")
    println("CHAT LOG: " + user.chatLog)

    user.logout
  }
}