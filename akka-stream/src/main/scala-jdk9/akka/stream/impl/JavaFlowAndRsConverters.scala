/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.stream.impl

import java.util.concurrent.Flow

import akka.annotation.InternalApi
import org.{ reactivestreams => rs }
import JavaFlowAndRsConverters.Implicits._
import org.reactivestreams.Subscription
  
// TODO looking for a good name here
object JavaFlowAndRsConverters {
  
  object Implicits {
    final implicit class FlowPublisherConverter[T](val p: rs.Publisher[T]) extends AnyVal {
      def asJava: Flow.Publisher[T] = 
        if (p eq null) null else JavaFlowAndRsConverters.asJava(p)
    }
    final implicit class RsPublisherConverter[T](val p: Flow.Publisher[T]) extends AnyVal {
      def asRs: rs.Publisher[T] = JavaFlowAndRsConverters.asRs(p)
    }
    
    final implicit class FlowSubscriberConverter[T](val s: rs.Subscriber[T]) extends AnyVal {
      def asJava: Flow.Subscriber[T] = JavaFlowAndRsConverters.asJava(s)
    }
    final implicit class RsSubscriberConverter[T](val s: Flow.Subscriber[T]) extends AnyVal {
      def asRs: rs.Subscriber[T] = JavaFlowAndRsConverters.asRs(s)
    }
    
    final implicit class FlowSubscriptionConverter[T](val s: rs.Subscription[T]) extends AnyVal {
      def asJava: Flow.Subscription[T] = JavaFlowAndRsConverters.asJava(s)
    }
    final implicit class RsSubscriptionConverter[T](val s: Flow.Subscription[T]) extends AnyVal {
      def asRs: rs.Subscription[T] = JavaFlowAndRsConverters.asRs(s)
    }
  }
  
  final def asJava[T](p: rs.Publisher[T]): Flow.Publisher[T] = new RsPublisherToJavaFlowAdapter[T](p)
  final def asRs[T](p: Flow.Publisher[T]): rs.Publisher[T] = new JavaFlowPublisherToRsAdapter[T](p)
  
  final def asJava[T](s: rs.Subscription[T]): Flow.Subscription = new RsSubscriptionToJavaFlowAdapter(s)
  final def asRs[T](s: Flow.Subscription[T]): rs.Subscription = new JavaFlowSubscriptionToRsAdapter(s)
  
  final def asJava[T](s: rs.Subscriber[T]): Flow.Subscriber[T] = s match {
    case null => null // TODO THINK about not wrapping too many timescd s
    case _ => new RsSubscriberToJavaFlowAdapter[T](s)
  }
  final def asRs[T](s: Flow.Subscriber[T]): rs.Subscriber[T] = new JavaFlowSubscriberToRsAdapter[T](s)
  
  final def asJava[T](p: rs.Processor[T]): Flow.Processor[T] = ???
  final def asRs[T](p: Flow.Processor[T]): rs.Processor[T] = ???
}

/** INTERNAL API: Adapters are not meant to be touched directly */ 
@InternalApi private[akka] final class JavaFlowPublisherToRsAdapter[T](val delegate: Flow.Publisher[T]) extends rs.Publisher[T] {
  override def subscribe(rsSubscriber: rs.Subscriber[_ >: T]): Unit = 
    delegate.subscribe(rsSubscriber.asJava)
}
/** INTERNAL API: Adapters are not meant to be touched directly */ 
@InternalApi private[akka] final class RsPublisherToJavaFlowAdapter[T](val delegate: rs.Publisher[T]) extends Flow.Publisher[T] {
  override def subscribe(javaSubscriber: Flow.Subscriber[_ >: T]): Unit = 
    delegate.subscribe(javaSubscriber.asRs)
}

/** INTERNAL API: Adapters are not meant to be touched directly */ 
@InternalApi private[akka] final class RsSubscriberToJavaFlowAdapter[T](val delegate: rs.Subscriber[T]) extends Flow.Subscriber[T] {
  override def onError(t: Throwable): Unit =
    delegate.onError(t)

  override def onComplete(): Unit =
    delegate.onComplete()

  override def onNext(elem: T): Unit =
    delegate.onNext(elem)

  override def onSubscribe(s: Subscription): Unit =
    delegate.onSubscribe(s.asJava)
}

/** INTERNAL API: Adapters are not meant to be touched directly */ 
@InternalApi private[akka] final class RsSubscriptionToJavaFlowAdapter(val s: rs.Subscription) extends Flow.Subscription {
  override def cancel(): Unit = s.cancel()

  override def request(n: Long): Unit = s.request(n)
} 
/** INTERNAL API: Adapters are not meant to be touched directly */ 
@InternalApi private[akka] final class JavaFlowSubscriptionToRsAdapter(val s: Flow.Subscription) extends rs.Subscription {
  override def cancel(): Unit = s.cancel()

  override def request(n: Long): Unit = s.request(n)
} 
