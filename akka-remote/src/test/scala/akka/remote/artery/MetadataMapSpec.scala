/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.util.OptionVal
import org.scalatest.{ Matchers, WordSpec }

class MetadataMapSpec extends WordSpec with Matchers {

  "MetadataMap" must {
    "hasValueFor" in {
      val a = MetadataMap[String]()

      a.hasValueFor(0) should ===(false)
      a.set(0, "0")
      a.hasValueFor(0) should ===(true)
      a.hasValueFor(1) should ===(false)

      a.clear()
      a.isEmpty should ===(true)
      a.nonEmpty should ===(false)
      a.hasValueFor(12) should ===(false)
      a.hasValueFor(0) should ===(false)
      a.set(0, "0")
      a.hasValueFor(0) should ===(true)
    }
    "setting values" in {
      val a = MetadataMap[String]()

      a(0) should ===(OptionVal.None)
      a.usedSlots should ===(0)
      a.set(0, "0")
      a(0) should ===(OptionVal.Some("0"))
      a.usedSlots should ===(1)

      a.set(0, "1")
      a(0) should ===(OptionVal.Some("1"))
      a.usedSlots should ===(1)

      a.set(0, null)
      a(0) should ===(OptionVal.None)
      a.usedSlots should ===(0)
    }
  }

}
