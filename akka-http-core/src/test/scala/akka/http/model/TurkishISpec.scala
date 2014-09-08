/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.util.Locale

import akka.http.util._

import org.scalatest.{ Matchers, WordSpec }

class TurkishISpec extends WordSpec with Matchers {
  "Model" should {
    "not suffer from turkish-i problem" in {
      val charsetCons = Class.forName("akka.http.model.HttpCharsets$").getDeclaredConstructor()
      charsetCons.setAccessible(true)

      val previousLocale = Locale.getDefault

      try {
        // recreate HttpCharsets in turkish locale
        Locale.setDefault(new Locale("tr", "TR"))

        val testString = "ISO-8859-1"
        // demonstrate difference between toRootLowerCase and toLowerCase(turkishLocale)
        testString.toLowerCase should not equal (testString.toRootLowerCase)

        val newCharsets = charsetCons.newInstance().asInstanceOf[HttpCharsets.type]
        newCharsets.getForKey("iso-8859-1") shouldEqual Some(newCharsets.`ISO-8859-1`)
      } finally {
        Locale.setDefault(previousLocale)
      }
    }
  }
}
