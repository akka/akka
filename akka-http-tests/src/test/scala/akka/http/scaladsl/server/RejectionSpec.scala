/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.server

import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.collection.JavaConverters._

class RejectionSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  "The Transformation Rejection" should {

    "map to and from Java" in {
      import akka.http.javadsl.{ server ⇒ jserver }
      val rejections = List(RequestEntityExpectedRejection)
      val jrejections: java.lang.Iterable[jserver.Rejection] =
        rejections.map(_.asInstanceOf[jserver.Rejection]).asJava
      val jresult = TransformationRejection(identity).getTransform.apply(jrejections)

      val result = jresult.asScala.map(r ⇒ r.asInstanceOf[Rejection])
      result should ===(rejections)
    }
  }
}
