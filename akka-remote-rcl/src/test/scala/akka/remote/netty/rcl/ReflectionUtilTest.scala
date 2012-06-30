package akka.remote.netty.rcl

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class ReflectionUtilTest extends FlatSpec with ShouldMatchers {

  class Foo(var foo: String)
  class Bar(foo: String, var bar: String) extends Foo(foo)

  "ReflectionUtil" should "be able to get a private field" in {
    ReflectionUtil.getField("foo", classOf[Foo]) should not be null
    ReflectionUtil.getField("foo", classOf[Bar]) should not be null
    ReflectionUtil.getField("bar", classOf[Bar]) should not be null
  }

  "ReflectionUtil" should "be able to get a private field value" in {
    val bar = new Bar("foo", "bar")
    ReflectionUtil.getFieldValue("foo", bar) should equal("foo")
    ReflectionUtil.getFieldValue("bar", bar) should equal("bar")
  }

  "ReflectionUtil" should "be able to set a private field value" in {
    val bar = new Bar("foo", "bar")
    bar.foo should equal("foo")
    bar.bar should equal("bar")
    ReflectionUtil.setFieldValue("foo", bar, "boom")
    ReflectionUtil.setFieldValue("bar", bar, "bam")
    bar.foo should equal("boom")
    bar.bar should equal("bam")
  }

  "ReflectionUtil" should "be able to invoke a public method with no args" in {
    val bar = new Bar("foo", "bar")
    ReflectionUtil.invokeMethod("foo", bar) should equal("foo")
    ReflectionUtil.invokeMethod("bar", bar) should equal("bar")
    intercept[NoSuchElementException] {
      ReflectionUtil.invokeMethod("notHere", bar)
    }
  }

}
