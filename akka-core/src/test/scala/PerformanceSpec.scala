package se.scalablesolutions.akka

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import net.lag.logging.Logger

/**
 * The Computer Language Benchmarks Game
 * <p/>
 * URL: [http://shootout.alioth.debian.org/]
 * <p/>
 * Contributed by Julien Gaugaz.
 * <p/>
 * Inspired by the version contributed by Yura Taras and modified by Isaac Gouy.
 */
class PerformanceSpec extends JUnitSuite {

  @Test
  def dummyTest = assert(true)

//  @Test
  def benchAkkaActorsVsScalaActors = {

    def stressTestAkkaActors(nrOfMessages: Int, nrOfActors: Int, sleepTime: Int): Long = {
      import se.scalablesolutions.akka.actor.Actor

      abstract class Colour
      case object RED extends Colour
      case object YELLOW extends Colour
      case object BLUE extends Colour
      case object FADED extends Colour

      val colours = Array[Colour](BLUE, RED, YELLOW)

      case class Meet(from: Actor, colour: Colour)
      case class Change(colour: Colour)
      case class MeetingCount(count: Int)
      case class ExitActor(actor: Actor, reason: String)

      var totalTime = 0L

      class Mall(var nrMeets: Int, numChameneos: Int) extends Actor {
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
              println("time: " + totalTime)
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

      case class Chameneo(var mall: Mall, var colour: Colour, cid: Int) extends Actor {
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
            exit
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

      val mall = new Mall(nrOfMessages, nrOfActors)
      mall.startChameneos
      Thread.sleep(sleepTime)
      totalTime
    }

    def stressTestScalaActors(nrOfMessages: Int, nrOfActors: Int, sleepTime: Int): Long = {
      var totalTime = 0L

      import scala.actors._
      import scala.actors.Actor._

      abstract class Colour
      case object RED extends Colour
      case object YELLOW extends Colour
      case object BLUE extends Colour
      case object FADED extends Colour

      val colours = Array[Colour](BLUE, RED, YELLOW)

      case class Meet(colour: Colour)
      case class Change(colour: Colour)
      case class MeetingCount(count: Int)


      class Mall(var n: Int, numChameneos: Int) extends Actor {
        var waitingChameneo: Option[OutputChannel[Any]] = None
        var startTime: Long = 0L

        start()

        def startChameneos(): Unit = {
          startTime = System.currentTimeMillis
          var i = 0
          while (i < numChameneos) {
            Chameneo(this, colours(i % 3), i).start()
            i = i + 1
          }
        }

        def act() {
          var sumMeetings = 0
          var numFaded = 0
          loop {
            react {

              case MeetingCount(i) => {
                numFaded = numFaded + 1
                sumMeetings = sumMeetings + i
                if (numFaded == numChameneos) {
                  totalTime = System.currentTimeMillis - startTime
                  exit()
                }
              }

              case msg@Meet(c) => {
                if (n > 0) {
                  waitingChameneo match {
                    case Some(chameneo) =>
                      n = n - 1
                      chameneo.forward(msg)
                      waitingChameneo = None
                    case None =>
                      waitingChameneo = Some(sender)
                  }
                } else {
                  waitingChameneo match {
                    case Some(chameneo) =>
                      chameneo ! Exit(this, "normal")
                    case None =>
                  }
                  sender ! Exit(this, "normal")
                }
              }

            }
          }
        }
      }

      case class Chameneo(var mall: Mall, var colour: Colour, id: Int) extends Actor {
        var meetings = 0

        def act() {
          loop {
            mall ! Meet(colour)
            react {
              case Meet(otherColour) =>
                colour = complement(otherColour)
                meetings = meetings + 1
                sender ! Change(colour)
              case Change(newColour) =>
                colour = newColour
                meetings = meetings + 1
              case Exit(_, _) =>
                colour = FADED
                sender ! MeetingCount(meetings)
                exit()
            }
          }
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

        override def toString() = id + "(" + colour + ")"
      }

      val mall = new Mall(nrOfMessages, nrOfActors)
      mall.startChameneos
      Thread.sleep(sleepTime)
      totalTime
    }

    Logger.INFO
    println("===========================================")
    println("== Benchmark Akka Actors vs Scala Actors ==")

    var nrOfMessages = 2000000
    var nrOfActors = 4
    var akkaTime = stressTestAkkaActors(nrOfMessages, nrOfActors, 1000 * 30)
    var scalaTime = stressTestScalaActors(nrOfMessages, nrOfActors, 1000 * 40)
    var ratio: Double = scalaTime.toDouble / akkaTime.toDouble

    println("\tNr of messages:\t" + nrOfMessages)
    println("\tNr of actors:\t" + nrOfActors)
    println("\tAkka Actors:\t" + akkaTime + "\t milliseconds")
    println("\tScala Actors:\t" + scalaTime + "\t milliseconds")
    println("\tAkka is " + ratio + " times faster\n")
    println("===========================================")
    assert(true)
  }
}
