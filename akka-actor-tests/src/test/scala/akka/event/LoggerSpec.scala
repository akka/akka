/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import com.typesafe.config.ConfigFactory
import akka.actor.{ Actor, ActorRef }
import akka.event.Logging.InitializeLogger
import akka.testkit.{ TestProbe, AkkaSpec }

object LoggerSpec {

  val config = ConfigFactory.parseString(
    """akka.event-handlers = ["akka.event.LoggerSpec$MyLog1", "akka.event.LoggerSpec$MyLog2"]""")

  case class SetTarget(ref: ActorRef, qualifier: Int)

  class MyLog1 extends MyLog(1)
  class MyLog2 extends MyLog(2)
  class MyLog(qualifier: Int) extends Actor {
    var dst: Option[ActorRef] = None
    override def receive: Receive = {
      case InitializeLogger(bus) ⇒
        bus.subscribe(context.self, classOf[SetTarget])
        sender ! Logging.LoggerInitialized
      case SetTarget(ref, `qualifier`) ⇒
        dst = Some(ref)
        ref ! "OK"
      case e: Logging.LogEvent ⇒
        dst foreach { _ ! e.message }
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LoggerSpec extends AkkaSpec(LoggerSpec.config) {

  import LoggerSpec._

  "ActorSystem logging" must {

    "be able to use several loggers" in {
      val probe1 = TestProbe()
      val probe2 = TestProbe()
      system.eventStream.publish(SetTarget(probe1.ref, 1))
      probe1.expectMsg("OK")
      system.eventStream.publish(SetTarget(probe2.ref, 2))
      probe2.expectMsg("OK")

      system.log.warning("log it")
      probe1.expectMsg("log it")
      probe2.expectMsg("log it")
    }
  }
}
