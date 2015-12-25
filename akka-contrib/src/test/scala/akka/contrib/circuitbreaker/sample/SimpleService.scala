package akka.contrib.circuitbreaker.sample

import akka.actor.{ ActorLogging, Actor }
import scala.concurrent.duration._
import scala.util.Random

//#simple-service
object SimpleService {
  case class Request(content: String)
  case class Response(content: Either[String, String])
  case object ResetCount
}

/**
 * This is a simple actor simulating a service
 * - Becoming slower with the increase of frequency of input requests
 * - Failing around 30% of the requests
 */
class SimpleService extends Actor with ActorLogging {
  import SimpleService._

  var messageCount = 0

  import context.dispatcher

  context.system.scheduler.schedule(1.second, 1.second, self, ResetCount)

  override def receive = {
    case ResetCount ⇒
      messageCount = 0

    case Request(content) ⇒
      messageCount += 1
      // simulate workload
      Thread.sleep(100 * messageCount)
      // Fails around 30% of the times
      if (Random.nextInt(100) < 70) {
        sender ! Response(Right(s"Successfully processed $content"))
      } else {
        sender ! Response(Left(s"Failure processing $content"))
      }

  }
}
//#simple-service