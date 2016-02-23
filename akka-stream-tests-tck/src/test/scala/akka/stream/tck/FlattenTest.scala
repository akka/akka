/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.stream.impl.ConstantFun
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher

class FlattenTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val s1 = Source(iterable(elements / 2))
    val s2 = Source(iterable((elements + 1) / 2))
    Source(List(s1, s2)).flatMapConcat(ConstantFun.scalaIdentityFunction).runWith(Sink.asPublisher(false))
  }

}
