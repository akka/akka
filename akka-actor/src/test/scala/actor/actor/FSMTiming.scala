/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import java.util.concurrent.TimeUnit

object FSMTiming {

  case object Msg1
  case object Msg2

  class Flipper extends Actor with FSM[Int, Null] {
    startWith(0, null)
    when(0) {
      case Event(StateTimeout, _) => stop(Failure("received StateTimeout unexpectedly"))
      case Event(Msg1, _) => goto(1) until 1
    }
    when(1) {
      case Event(StateTimeout, _) => goto(0)
      case Event(Msg2, _) => goto(0)
    }
  }

  case class DoStop(delay : Long)

  class Timer extends Actor with FSM[Int, Null] {
    startWith(0, null)
    when(0) {
      case Event(DoStop(delay), _) if delay > 0 => setTimer(delay, DoStop(0))
      case Event(DoStop(_), _) => stop
    }
  }

}

class FSMTiming extends JUnitSuite {
  import FSMTiming._

  object Sleep {
    val random = new scala.util.Random
    def apply(n : Int) = Thread.sleep(n)
    def apply(from : Int, to : Int) = Thread.sleep(from + random.nextInt(to - from))
  }

  @Test
  def testCorrectStateTimeout = {
    val actor = Actor.actorOf[Flipper].start
    (1 to 50) foreach { x => 
      Sleep(50)
      actor ! Msg1
      Sleep(0, 4)
      actor ! Msg2
    }
    assert(actor.isRunning)
    actor.stop
  }

  @Test
  def testSetTimer = {
    val actor = Actor.actorOf[Timer].start
    actor ! DoStop(100)
    Sleep(150)
    assert(actor.isShutdown)
  }
}

