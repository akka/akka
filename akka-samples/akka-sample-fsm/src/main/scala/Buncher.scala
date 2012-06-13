/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>.
 */
package sample.fsm.buncher

import akka.actor.ActorRefFactory
import scala.reflect.ClassTag
import akka.util.Duration
import akka.actor.{ FSM, Actor, ActorRef }

/*
* generic typed object buncher.
*
* To instantiate it, use the factory method like so:
*   Buncher(100, 500)(x : List[AnyRef] => x foreach println)
* which will yield a fully functional ActorRef.
* The type of messages allowed is strongly typed to match the
* supplied processing method; other messages are discarded (and
* possibly logged).
*/
object GenericBuncher {
  trait State
  case object Idle extends State
  case object Active extends State

  case object Flush // send out current queue immediately
  case object Stop // poison pill

  class MsgExtractor[A: Manifest] {
    def unapply(m: AnyRef): Option[A] = {
      if (ClassTag.fromClass(m.getClass) <:< manifest[A]) {
        Some(m.asInstanceOf[A])
      } else {
        None
      }
    }
  }
}

abstract class GenericBuncher[A: Manifest, B](val singleTimeout: Duration, val multiTimeout: Duration)
  extends Actor with FSM[GenericBuncher.State, B] {
  import GenericBuncher._
  import FSM._

  protected def empty: B
  protected def merge(acc: B, elem: A): B
  protected def send(acc: B): Unit

  protected def flush(acc: B) = {
    send(acc)
    cancelTimer("multi")
    goto(Idle) using empty
  }

  val Msg = new MsgExtractor[A]

  startWith(Idle, empty)

  when(Idle) {
    case Event(Msg(m), acc) ⇒
      setTimer("multi", StateTimeout, multiTimeout, false)
      goto(Active) using merge(acc, m)
    case Event(Flush, _) ⇒ stay
    case Event(Stop, _)  ⇒ stop
  }

  when(Active, stateTimeout = singleTimeout) {
    case Event(Msg(m), acc) ⇒
      stay using merge(acc, m)
    case Event(StateTimeout, acc) ⇒
      flush(acc)
    case Event(Flush, acc) ⇒
      flush(acc)
    case Event(Stop, acc) ⇒
      send(acc)
      cancelTimer("multi")
      stop
  }

  initialize
}

object Buncher {
  case class Target(target: ActorRef) // for setting the target for default send action

  val Stop = GenericBuncher.Stop // make special message objects visible for Buncher clients
  val Flush = GenericBuncher.Flush
}

class Buncher[A: Manifest](singleTimeout: Duration, multiTimeout: Duration)
  extends GenericBuncher[A, List[A]](singleTimeout, multiTimeout) {

  import Buncher._

  private var target: Option[ActorRef] = None
  protected def send(acc: List[A]): Unit = if (target.isDefined) target.get ! acc.reverse

  protected def empty: List[A] = Nil

  protected def merge(l: List[A], elem: A) = elem :: l

  whenUnhandled {
    case Event(Target(t), _) ⇒
      target = Some(t)
      stay
  }

}
