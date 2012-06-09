package akka.remote.netty.rcl

import org.scalatest.FlatSpec
import org.scalatest.matchers._

class ByteCodeInspectorSpec extends FlatSpec with ShouldMatchers {

  "ByteCodeInspector" should "be able to list fqn of *most* of the classes the given class references" in {
    val refs = ByteCodeInspector.findReferencedClassesFor(classOf[ToInspect]) toSet

    refs should contain("java.math.BigDecimal") // fields
    refs should contain("java.util.Calendar") // referenced methods 
    refs should contain("java.util.TimeZone") // and
    refs should contain("java.util.Locale") // params
    refs should contain("java.util.ArrayList") // params

    // should contain itself
    refs should contain("akka.remote.netty.rcl.ByteCodeInspectorSpec$ToInspect")

    // this is where *most* comes from, constant pool does not include methods the class defines
    // it makes sense as this information is available in the method pool
    // we are not parsing the method pool therefore we lack to get the references from our method definitions
    // but the same class could be referenced elswhere
    refs should not contain ("java.lang.String")
    refs should not contain ("java.util.Date")
  }

  class ToInspect() {

    val field: java.math.BigDecimal = null

    def methodParamsRefsNotAvailable(a: String) {
      java.util.Calendar.getInstance(null /*TimeZone*/ , null /*Locale*/ )
    }

    def returningNotAvailable(): java.util.Date = null

    def referenced() {
      val available = new java.util.ArrayList()
    }
  }

}