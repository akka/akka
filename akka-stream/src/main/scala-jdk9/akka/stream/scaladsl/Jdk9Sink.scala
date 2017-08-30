/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.stream.scaladsl


import java.util.{ concurrent => juc }

import akka.NotUsed
import akka.stream.impl.JavaFlowAndRsConverters
import org.{ reactivestreams => rs }


trait Jdk9Sink {
  import JavaFlowAndRsConverters.Implicits._
  
  final def asJavaFlowPublisher[T](fanout: Boolean): Sink[T, juc.Flow.Publisher[T]] = 
    Sink.asPublisher[T](fanout).mapMaterializedValue(_.asJava)
  
  final def fromJavaFlowSubscriber[T](s: juc.Flow.Subscriber[T]): Sink[T, NotUsed] =
    Sink.fromSubscriber(s.asRs)
  
}

object Jdk9Sink extends Jdk9Sink
