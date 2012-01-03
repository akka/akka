package akka.camel

import component.BlockingOrNotTypeConverter
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import akka.util.duration._

class BlockingOrNotTypeConverterTest extends FlatSpec with ShouldMatchers{


  "TypeConverter" should "convert NonBlocking" in {
    convert("NonBlocking") should  be(NonBlocking)
  }

  it should "convert Blocking with seconds" in {
    convert(Blocking(10 seconds).toString) should be (Blocking(10 seconds))
  }

  it should "convert Blocking with millis" in {
    convert(Blocking(10 millis).toString) should be (Blocking(10 millis))
  }

  def convert(value: String): BlockingOrNot = {
    BlockingOrNotTypeConverter.convertTo(classOf[BlockingOrNot], value)
  }

}