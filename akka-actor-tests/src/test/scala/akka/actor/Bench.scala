/* The Computer Language Benchmarks Game
   http://shootout.alioth.debian.org/
   contributed by Julien Gaugaz
   inspired by the version contributed by Yura Taras and modified by Isaac Gouy
 */

package akka.actor

object Chameneos {

  sealed trait ChameneosEvent
  final case class Meet(from: ActorRef, colour: Colour) extends ChameneosEvent
  final case class Change(colour: Colour) extends ChameneosEvent
  final case class MeetingCount(count: Int) extends ChameneosEvent
  case object Exit extends ChameneosEvent

  abstract sealed class Colour
  case object RED extends Colour
  case object YELLOW extends Colour
  case object BLUE extends Colour
  case object FADED extends Colour

  val colours = Array[Colour](BLUE, RED, YELLOW)

  var start = 0L
  var end = 0L

  class Chameneo(var mall: ActorRef, var colour: Colour, cid: Int) extends Actor {
    var meetings = 0
    mall ! Meet(self, colour)

    def receive = {
      case Meet(from, otherColour) =>
        colour = complement(otherColour)
        meetings = meetings + 1
        from ! Change(colour)
        mall ! Meet(self, colour)

      case Change(newColour) =>
        colour = newColour
        meetings = meetings + 1
        mall ! Meet(self, colour)

      case Exit =>
        colour = FADED
        sender() ! MeetingCount(meetings)
    }

    def complement(otherColour: Colour): Colour = colour match {
      case RED =>
        otherColour match {
          case RED    => RED
          case YELLOW => BLUE
          case BLUE   => YELLOW
          case FADED  => FADED
        }
      case YELLOW =>
        otherColour match {
          case RED    => BLUE
          case YELLOW => YELLOW
          case BLUE   => RED
          case FADED  => FADED
        }
      case BLUE =>
        otherColour match {
          case RED    => YELLOW
          case YELLOW => RED
          case BLUE   => BLUE
          case FADED  => FADED
        }
      case FADED => FADED
    }

    override def toString = s"$cid($colour)"
  }

  class Mall(var n: Int, numChameneos: Int) extends Actor {
    var waitingChameneo: Option[ActorRef] = None
    var sumMeetings = 0
    var numFaded = 0

    override def preStart() = {
      for (i <- 0 until numChameneos) context.actorOf(Props(new Chameneo(self, colours(i % 3), i)))
    }

    def receive = {
      case MeetingCount(i) =>
        numFaded += 1
        sumMeetings += i
        if (numFaded == numChameneos) {
          Chameneos.end = System.currentTimeMillis
          context.stop(self)
        }

      case msg: Meet =>
        if (n > 0) {
          waitingChameneo match {
            case Some(chameneo) =>
              n -= 1
              chameneo ! msg
              waitingChameneo = None
            case None => waitingChameneo = Some(sender())
          }
        } else {
          waitingChameneo.foreach(_ ! Exit)
          sender() ! Exit
        }
    }
  }

  def run(): Unit = {
    //    System.setProperty("akka.config", "akka.conf")
    Chameneos.start = System.currentTimeMillis
    val system = ActorSystem()
    system.actorOf(Props(new Mall(1000000, 4)))
    Thread.sleep(10000)
    println("Elapsed: " + (end - start))
    system.terminate()
  }

  def main(args: Array[String]): Unit = run()
}
