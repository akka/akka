package akka.streams
package ops

import rx.async.api.Producer
import akka.streams.Operation.Span

object SpanImpl {
  def apply[I](span: Span[I]): OpInstance[I, Producer[I]] =
    new OpInstanceStateMachine[I, Producer[I]] {
      def initialState = WaitingForRequest

      var subStreamsRequested = 0
      var undeliveredElements = 0

      lazy val WaitingForRequest: State = {
        case RequestMore(n) ⇒
          subStreamsRequested += n
          become(WaitingForFirstElement)
          RequestMore(1)
        case Complete ⇒ Complete
        case e: Error ⇒ e
      }
      lazy val WaitingForFirstElement: State = {
        case RequestMore(n) ⇒
          subStreamsRequested += n; Continue
        case Emit(i) ⇒
          // FIXME: case when first element matches predicate, in which case we could emit a singleton Producer
          subStreamsRequested -= 1
          become(WaitingForPublisher)
          Emit(InternalPublisherTemplate[I] { requestor ⇒
            publisher ⇒
              become(Running(requestor, publisher))

              new PublisherHandler[I] {
                var cachedFirst: AnyRef = i.asInstanceOf[AnyRef]

                def handle(result: BackchannelResult): Result[Producer[I]] = result match {
                  case RequestMore(n) ⇒
                    undeliveredElements += n

                    val emit =
                      if (cachedFirst != null) {
                        val res = publisher.emit(cachedFirst.asInstanceOf[I])
                        undeliveredElements -= 1
                        cachedFirst = null
                        res
                      } else Continue

                    emit ~ (if (undeliveredElements > 0) requestor.requestMore(1) else Continue)
                }
              }
          })

        case Complete ⇒ Complete
        case e: Error ⇒ e
      }
      lazy val WaitingForPublisher: State = {
        case RequestMore(n) ⇒ subStreamsRequested += n; Continue
      }
      // FIXME: properly deal with error / completion of source
      def Running(requestor: SubscriptionResults, publisher: PublisherResults[I]): State = {
        case RequestMore(n) ⇒
          subStreamsRequested += n; Continue
        case Emit(i) ⇒
          val emit = publisher.emit(i)
          undeliveredElements -= 1
          if (span.p(i))
            if (subStreamsRequested > 0) {
              become(WaitingForFirstElement)
              emit ~ publisher.complete ~ RequestMore(1)
            } else {
              become(WaitingForRequest)
              emit ~ publisher.complete
            }
          else emit ~ (if (undeliveredElements > 0) requestor.requestMore(1) else Continue)
        case e: EmitMany[_] ⇒ handleMany(e)
      }
    }
}
