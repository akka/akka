package akka.streams
package ops

import rx.async.api.Producer

object FlattenImpl {
  def apply[I](flatten: Flatten[I]): OpInstance[Producer[I], I] =
    new OpInstanceStateMachine[Producer[I], I] {
      import flatten._

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
          Subscribe(i) { subscription ⇒
            val handler = ReadSubstream(subscription, remaining)
            become(handler)
            handler.subHandler
          }
        case Complete ⇒ Complete
        case e: Error ⇒ e
      }
      case class ReadSubstream(subscription: SubscriptionResults, remaining: Int) extends State {
        // invariant: subscription.requestMore has been called for every
        // of the requested elements
        // each emitted element decrements that counter by one
        var curRemaining = remaining
        var closeAtEnd = false

        override def apply(v1: SimpleResult[Producer[I]]): Result[I] = v1 match {
          case RequestMore(n) ⇒
            curRemaining += n
            subscription.requestMore(n)
          case Emit(i) ⇒ throw new IllegalStateException("No element requested")
          case Complete ⇒
            closeAtEnd = true; Continue
          case e: Error ⇒
            // shortcut close result stream?
            // also see RxJava `mapManyDelayError`
            ???
        }

        def subHandler = new SubscriptionHandler[I, I] {
          def initial: Result[I] = subscription.requestMore(remaining)
          def handle(result: ForwardResult[I]): Result[I] = result match {
            case Emit(i) ⇒
              curRemaining -= 1
              Emit(i)
            case Complete ⇒
              if (curRemaining > 0) {
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
