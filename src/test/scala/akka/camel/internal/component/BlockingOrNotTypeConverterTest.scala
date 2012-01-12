package akka.camel.internal.component

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import akka.util.duration._
import akka.camel.{BlockingOrNot, Blocking, NonBlocking}

class BlockingOrNotTypeConverterTest extends FlatSpec with ShouldMatchers{


  "TypeConverter" should "convert NonBlocking" in {
    convert("NonBlocking") should  be(NonBlocking)
  }

  it should "convert Blocking with seconds" in {
    convert(BlockingOrNotTypeConverter.toString(Blocking(10 seconds))) should be (Blocking(10 seconds))
  }

  it should "convert Blocking with millis" in {
    convert(BlockingOrNotTypeConverter.toString(Blocking(10 millis))) should be (Blocking(10 millis))
  }

  def convert(value: String): BlockingOrNot = {
    BlockingOrNotTypeConverter.convertTo(classOf[BlockingOrNot], value)
  }

}