package akka.streams

import scala.concurrent.{ Future, ExecutionContext }
import rx.async.api.{ Consumer, Producer }
import rx.async.spi.{ Subscription, Subscriber }
import scala.util.control.NonFatal

object Combinators {
  implicit class CombinatorsImplicits[T](val producer: Producer[T]) extends AnyVal {
    def foreach(op: T ⇒ Unit)(implicit ec: ExecutionContext): Unit = {
      val subscriber = new ForeachSubscriber(op, ec)
      producer.getPublisher.subscribe(subscriber)
    }

    def map[U](op: T ⇒ U): Producer[U] = ???
    def flatMap[U](op: T ⇒ Producer[U]): Producer[U] = ???
    def andThen[U >: T](next: Producer[U]): Producer[U] = ???
    def span(pred: T ⇒ Boolean): Producer[Producer[T]] = ???
    def first: Producer[T] = ???
    def rest: Producer[T] = ???
    def headTail: Producer[(T, Producer[T])] = ???
    def takeWhile(pred: T ⇒ Boolean): Producer[T] = ???

    def connect(consumer: Consumer[T]): Unit = producer.getPublisher.subscribe(consumer.getSubscriber)
  }

  def Task(op: ⇒ Unit)(implicit ec: ExecutionContext): Future[Unit] =
    Future { try op catch { case NonFatal(e) ⇒ e.printStackTrace() } }

  private class ForeachSubscriber[T]( final val op: T ⇒ Unit, implicit val ec: ExecutionContext) extends Subscriber[T] {
    @volatile private var subscription: Subscription = _
    def onSubscribe(subscription: Subscription): Unit = Task {
      this.subscription = subscription
      this.subscription.requestMore(1)
    }

    def onError(cause: Throwable): Unit = ()
    def onComplete(): Unit = ()
    def onNext(element: T): Unit = Task {
      op(element)
      subscription.requestMore(1)
    }
  }

  implicit class FutureAsProducer[T](val future: Future[T]) extends AnyVal {
    def asProducer: Producer[T] = ???
  }
}
