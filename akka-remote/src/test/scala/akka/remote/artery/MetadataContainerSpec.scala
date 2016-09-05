/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.util.ByteString
import org.scalatest.{ Matchers, WordSpec }

class MetadataContainerSpec extends WordSpec with Matchers {

  "MetadataContainer" should {
    "with empty map" in {
      val map = new MetadataMap[ByteString]
      val container = new MetadataMapRendering(map)

      val rendered = container.render()
      val back = MetadataMapRendering.parseRaw(rendered)

      map.toString() should ===(back.metadataMap.toString())
    }
    "with 1 allocated in map" in {
      val map = new MetadataMap[ByteString]
      val container = new MetadataMapRendering(map)
      map.set(1, ByteString("!!!"))

      val rendered = container.render()
      val back = MetadataMapRendering.parseRaw(rendered)

      map.toString() should ===(back.metadataMap.toString())
    }

  }
}
