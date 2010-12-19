package akka.actor

import scala.reflect.ClassManifest
import akka.util.Duration

/*
 * generic typed object buncher.
 * 
 * To instantiate it, use the factory method like so:
 *   Buncher(100, 500)(x : List[AnyRef] => x foreach println)
 * which will yield a fully functional and started ActorRef.
 * The type of messages allowed is strongly typed to match the
 * supplied processing method; other messages are discarded (and
 * possibly logged).
 */
object Buncher {
	trait State
	case object Idle extends State
	case object Active extends State

	case object Flush // send out current queue immediately
	case object Stop // poison pill

	case class Data[A](start : Long, xs : List[A])
	
	def apply[A : Manifest](singleTimeout : Duration,
				multiTimeout : Duration)(f : List[A] => Unit) =
		Actor.actorOf(new Buncher[A](singleTimeout, multiTimeout).deliver(f))
}
	
class Buncher[A : Manifest] private (val singleTimeout : Duration, val multiTimeout : Duration)
		extends Actor with FSM[Buncher.State, Buncher.Data[A]] {
	import Buncher._
	import FSM._
		
	private val manifestA = manifest[A]
		
	private var send : List[A] => Unit = _
	private def deliver(f : List[A] => Unit) = { send = f; this }
		
	private def now = System.currentTimeMillis
	private def check(m : AnyRef) = ClassManifest.fromClass(m.getClass) <:< manifestA

	startWith(Idle, Data(0, Nil))
		
	when(Idle) {
		case Event(m : AnyRef, _) if check(m) =>
			goto(Active) using Data(now, m.asInstanceOf[A] :: Nil)
		case Event(Flush, _) => stay
		case Event(Stop, _) => stop
	}
		
	when(Active, stateTimeout = Some(singleTimeout)) {
		case Event(m : AnyRef, Data(start, xs)) if check(m) =>
			val l = m.asInstanceOf[A] :: xs
			if (now - start > multiTimeout.toMillis) {
				send(l.reverse)
				goto(Idle) using Data(0, Nil)
			} else {
				stay using Data(start, l)
			}
		case Event(StateTimeout, Data(_, xs)) =>
			send(xs.reverse)
			goto(Idle) using Data(0, Nil)
		case Event(Flush, Data(_, xs)) =>
			send(xs.reverse)
			goto(Idle) using Data(0, Nil)
		case Event(Stop, Data(_, xs)) =>
			send(xs.reverse)
			stop
	}
		
	initialize
}
