package test

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.actor.Actor

class PerformanceTest extends JUnitSuite {
  abstract class Colour
  case object RED extends Colour
  case object YELLOW extends Colour
  case object BLUE extends Colour
  case object FADED extends Colour

  val colours = Array(BLUE, RED, YELLOW)

  case class Meet(from: Actor, colour: Colour)
  case class Change(colour: Colour)
  case class MeetingCount(count: int)
  case class ExitActor(actor: Actor, reason: String)

  var totalTime = -1

  class Mall(var nrMeets: int, numChameneos: int) extends Actor {
    var waitingChameneo: Option[Actor] = None
    var sumMeetings = 0
    var numFaded = 0
    var startTime: Long = 0L

    start

    def startChameneos(): Unit = {
      startTime = System.currentTimeMillis
      var i = 0
      while (i < numChameneos) {
        Chameneo(this, colours(i % 3), i).start
        i = i + 1
      }
    }

    def receive = {
      case MeetingCount(i) => {
        numFaded = numFaded + 1
        sumMeetings = sumMeetings + i
        if (numFaded == numChameneos) {
          totalTime = System.currentTimeMillis - startTime
          println("Total time Akka Actors: " + totalTime)
          exit
        }
      }

      case msg@Meet(a, c) => {
        if (nrMeets > 0) {
          waitingChameneo match {
            case Some(chameneo) =>
              nrMeets = nrMeets - 1
              chameneo ! msg
              waitingChameneo = None
            case None =>
              waitingChameneo = sender
          }
        } else {
          waitingChameneo match {
            case Some(chameneo) =>
              chameneo ! ExitActor(this, "normal")
            case None =>
          }
          sender.get ! ExitActor(this, "normal")
        }
      }
    }
  }

  case class Chameneo(var mall: Mall, var colour: Colour, cid: int) extends Actor {
    var meetings = 0

    override def start = {
      val r = super.start
      mall ! Meet(this, colour)
      r
    }

    override def receive: PartialFunction[Any, Unit] = {
      case Meet(from, otherColour) =>
        colour = complement(otherColour)
        meetings = meetings + 1
        from ! Change(colour)
        mall ! Meet(this, colour)
      case Change(newColour) =>
        colour = newColour
        meetings = meetings + 1
        mall ! Meet(this, colour)
      case ExitActor(_, _) =>
        colour = FADED
        sender.get ! MeetingCount(meetings)
      //exit
    }

    def complement(otherColour: Colour): Colour = {
      colour match {
        case RED => otherColour match {
          case RED => RED
          case YELLOW => BLUE
          case BLUE => YELLOW
          case FADED => FADED
        }
        case YELLOW => otherColour match {
          case RED => BLUE
          case YELLOW => YELLOW
          case BLUE => RED
          case FADED => FADED
        }
        case BLUE => otherColour match {
          case RED => YELLOW
          case YELLOW => RED
          case BLUE => BLUE
          case FADED => FADED
        }
        case FADED => FADED
      }
    }

    override def toString() = cid + "(" + colour + ")"
  }

  @Test def dummy {assert(true)}

  @Test
  def stressTest {
    val N = 1000000
    val numChameneos = 4
    val mall = new Mall(N, numChameneos)
    mall.startChameneos
    Thread.sleep(1000 * 10)
    assert(totalTime < 5000)
  }
}
