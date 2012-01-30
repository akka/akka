
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.event.LoggingReceive
import scala.annotation.tailrec

object Errtest extends App {

  val config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)

  val sys = ActorSystem("ErrSys", config)
  val top = sys.actorOf(Props[Top], name = "top")

  for (n ← 1 to 100) {
    top ! "run " + n
    Thread.sleep(1000)
  }
}

class Top extends Actor {
  var c: ActorRef = _
  def receive = LoggingReceive {
    case x ⇒
      c = context.actorOf(Props[Child]);
      c ! "ok"
  }
}

class Child extends Actor {

  //throw new Error("Simulated ERR")
  blowUp(0)

  //not @tailrec
  private final def blowUp(n: Long): Long = {
    blowUp(n + 1) + 1
  }

  def receive = LoggingReceive {
    case x ⇒
    //context.system.shutdown();
  }
}