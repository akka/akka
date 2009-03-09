// =============================================
// 1. Import statements and Server messages

import scala.actors._
import scala.actors.Actor._

import scala.actors.behavior._
import scala.actors.behavior.Helpers._

sealed abstract class SampleMessage
case object Ping extends SampleMessage
case object Pong extends SampleMessage
case object OneWay extends SampleMessage
case object Die extends SampleMessage

// =============================================
// 2. Create the GenericServer by extending the GenericServer trait and override the 'body' method

class SampleServer extends GenericServer {

  // This method implements the core server logic and naturally has to be overridden
  override def body: PartialFunction[Any, Unit] = {
    case Ping =>
      println("Received Ping"); reply(Pong)

    case OneWay =>
      println("Received OneWay")

    case Die =>
      println("Received Die..dying...")
      throw new RuntimeException("Received Die message")
  }

  // GenericServer also has some callback life-cycle methods, such as init(..) and shutdown(..)
}

// =============================================
// 3. Wrap our SampleServer in  a GenericServerContainer and give it a name to be able to refer to it later.

object sampleServer1 extends GenericServerContainer("sample1", () => new SampleServer)
object sampleServer2 extends GenericServerContainer("sample2", () => new SampleServer)

// =============================================
// 4. Create a Supervisor configuration (and a SupervisorFactory) that is configuring our SampleServer (takes a list of 'Worker' configurations, one or many)

object factory extends SupervisorFactory {
  override protected def getSupervisorConfig: SupervisorConfig = {
    SupervisorConfig(
      RestartStrategy(AllForOne, 3, 10000),
       Worker(
        sampleServer1,
        LifeCycle(Permanent, 1000)) ::
       Worker(
        sampleServer2,
        LifeCycle(Permanent, 1000)) ::
      Nil)
  }
}

// =============================================
// 5. Create a new Supervisor with the custom factory

val supervisor = factory.newSupervisor

// =============================================
// 6. Start the Supervisor (which starts the server(s))

supervisor ! Start

// =============================================
// 7. Try to send a one way asyncronous message to our servers

sampleServer1 ! OneWay

// Try to get sampleServer2 from the Supervisor before sending a message
supervisor.getServer("sample2") match {
  case Some(server2) => server2 ! OneWay
  case None => println("server [sample2] could not be found")
}

// =============================================
// 8. Try to send an asyncronous message - receive a future - wait 100 ms (time-out) for the reply

val future = sampleServer1 !! Ping
val reply1 = future.receiveWithin(100) match {
  case Some(reply) =>
    println("Received reply: " + reply)
  case None =>
    println("Did not get a reply witin 100 ms")
}

// =============================================
// 9. Try to send a message (Die) telling the server to kill itself (throw an exception)

sampleServer1 ! Die

// =============================================
// 10. Send an asyncronous message and wait on a future. If it times out -> use error handler (in this case throw an exception). It is likely that this call will time out since the server is in the middle of recovering from failure.

val reply2 = try {
  sampleServer1 !!! (Ping, throw new RuntimeException("Time-out"), 10) // time out is set to 10 ms (very low on purpose)

} catch { case e => println("Expected exception: " + e.toString); Pong }

// =============================================
// 11. Server should be up again. Try the same call again

val reply3 = try {
  sampleServer1 !!! (Ping, throw new RuntimeException("Time-out"), 1000)
} catch { case e => println("Expected exception: " + e.toString); Pong }

// Also check server number 2
sampleServer2 ! Ping

// =============================================
// 11. Try to hotswap the server implementation

sampleServer1.hotswap(Some({
  case Ping =>
    println("Hotswapped Ping")
}))

// =============================================
// 12. Try the hotswapped server out

sampleServer1 ! Ping

// =============================================
// 13. Hotswap again

sampleServer1.hotswap(Some({
  case Pong =>
    println("Hotswapped again, now doing Pong")
    reply(Ping)
}))

// =============================================
// 14. Send an asyncronous message that will wait on a future. Method returns an Option[T] => if Some(result) -> return result, if None -> print out an info message (or throw an exception or do whatever you like...)

val reply4 = (sampleServer1 !!! Pong).getOrElse({println("Time out when sending Pong"); Ping})

// Same invocation with pattern matching syntax.

val reply5 = sampleServer1 !!! Pong match {
  case Some(result) => result
  case None => println("Time out when sending Pong"); Ping
}

// =============================================
// 15. Hotswap back to original implementation by passing in None

sampleServer1.hotswap(None)

// =============================================
// 16. Test the final hotswap by sending an async message

sampleServer1 !  Ping

// =============================================
// 17. Shut down the supervisor and its server(s)

supervisor ! Stop


