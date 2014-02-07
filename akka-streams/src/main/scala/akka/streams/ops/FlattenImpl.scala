package akka.streams
package ops

import akka.streams.Operation.{ Source, Flatten }

object FlattenImpl {
  def apply[I](flatten: Flatten[I]): OpInstance[Source[I], I] =
    new OpInstanceStateMachine[Source[I], I] {
      override def toString: String = "Flatten"

      def initialState = Waiting

      def Waiting: State = {
        case RequestMore(n) ⇒
          become(WaitingForElement(n))
          RequestMore(1)
        case Emit(i)  ⇒ throw new IllegalStateException("No element requested")
        case Complete ⇒ Complete
        case e: Error ⇒ e
      }

      def WaitingForElement(remaining: Int): State = {
        case RequestMore(n) ⇒
          become(WaitingForElement(remaining + n))
          RequestMore(0)
        case Emit(i) ⇒
          val handler = ReadSubstream(remaining)
          become(handler)
          Subscribe(i)(handler.onSubscribed)
        case Complete ⇒ Complete
        case e: Error ⇒ e
      }
      case class ReadSubstream(remaining: Int) extends State {
        var subscription: SubscriptionResults = _
        def onSubscribed(sub: SubscriptionResults): SubscriptionHandler[I, I] = {
          this.subscription = sub
          subHandler
        }

        // invariant: subscription.requestMore has been called for every
        // of the requested elements
        // each emitted element decrements that counter by one
        var curRemaining = remaining
        var closeAtEnd = false

        override def apply(v1: SimpleResult[Source[I]]): Result[I] = v1 match {
          case RequestMore(n) ⇒
            curRemaining += n
            println("Requesting more from sub")
            subscription.requestMore(n)
          case Emit(i) ⇒ throw new IllegalStateException("No element requested")
          case Complete ⇒
            closeAtEnd = true; Continue
          case e: Error ⇒
            // shortcut close result stream?
            // also see RxJava `mapManyDelayError`
            ???
        }

        val subHandler = new SubscriptionHandler[I, I] {
          def initial: Result[I] = subscription.requestMore(remaining)
          def handle(result: ForwardResult[I]): Result[I] = result match {
            case Emit(i) ⇒
              curRemaining -= 1
              Emit(i)
            case Complete ⇒
              if (closeAtEnd) Complete
              else if (curRemaining > 0) {
                become(WaitingForElement(curRemaining))
                RequestMore(1)
              } else {
                become(Waiting)
                Continue
              }
            case e: Error ⇒ e
          }
        }
      }
    }
}
