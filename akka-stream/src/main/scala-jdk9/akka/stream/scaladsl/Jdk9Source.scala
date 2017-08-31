/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.stream.scaladsl

import java.util.{ concurrent => juc }

import akka.NotUsed
import akka.stream.impl.JavaFlowAndRsConverters
import org.{ reactivestreams => rs }


trait Jdk9Source {
  import JavaFlowAndRsConverters.Implicits._
  
  final def fromJavaFlowPublisher[T](publisher: juc.Flow.Publisher[T]): Source[T, NotUsed] =
    Source.fromPublisher(publisher.asRs)
}

object Jdk9Source extends Jdk9Source
