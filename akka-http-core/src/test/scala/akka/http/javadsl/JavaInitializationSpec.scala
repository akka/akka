/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import org.scalatest.{ Matchers, WordSpec }

class JavaInitializationSpec extends WordSpec with Matchers {

  "LanguageRange" should {

    "initializes the right field" in {
      akka.http.scaladsl.model.headers.LanguageRange.`*` // first we touch the scala one, it should force init the Java one
      akka.http.javadsl.model.headers.LanguageRange.ALL // touching this one should not fail
      akka.http.javadsl.model.headers.LanguageRanges.ALL // this is recommended and should work well too
    }
  }
}
