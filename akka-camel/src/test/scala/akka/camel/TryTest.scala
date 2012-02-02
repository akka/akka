/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.Try
import akka.camel.Try._
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import akka.event.LoggingAdapter
import org.scalatest.{ BeforeAndAfterEach, WordSpec }

class TryTest extends WordSpec with MustMatchers with MockitoSugar with BeforeAndAfterEach {
  import org.mockito.Mockito._
  import org.mockito.Matchers._
  import org.mockito.Matchers.{ eq ⇒ the }

  implicit var log: LoggingAdapter = _

  override def beforeEach() {
    log = mock[LoggingAdapter]
  }

  "Safe executes block" in {
    var executed = false
    safe { executed = true }
    executed must be(true)
    verifyNoMoreInteractions(log)
  }

  "Safe swallows exception and logs it" in {
    safe(throw new Exception)
    verify(log).warning(any[String], any[Any])
  }

  "Try-otherwise runs otherwise and throws exception when the first block fails" in {
    var otherwiseCalled = false
    intercept[Exception] {
      Try(throw new Exception) otherwise (otherwiseCalled = true)
    }
    otherwiseCalled must be(true)
    verifyNoMoreInteractions(log)
  }

  "Try-otherwise swallows exception thrown in otherwise clause and logs it" in {
    val exceptionFromOtherwise = new RuntimeException("e2")
    intercept[RuntimeException] {
      Try(throw new RuntimeException("e1")) otherwise (throw exceptionFromOtherwise)
    }.getMessage must be("e1")
    verify(log, only()).warning(any[String], the(exceptionFromOtherwise))
  }

  "Try-otherwise doesnt run otherwise if first block doesnt fail" in {
    var otherwiseCalled = false
    Try(2 + 2) otherwise (otherwiseCalled = true)
    otherwiseCalled must be(false)
    verifyNoMoreInteractions(log)
  }

}
