package akka.camel

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import akka.util.duration._

class BlockingOrNotTest extends FlatSpec with ShouldMatchers{
  
  "TypeConverter" should "convert NonBlocking" in {
    BlockingOrNot.typeConverter.convertTo(classOf[BlockingOrNot], "NonBlocking") should be (NonBlocking)
  }

  it should "convert Blocking with seconds" in {
    BlockingOrNot.typeConverter.convertTo(classOf[BlockingOrNot], Blocking(10 seconds).toString) should be (Blocking(10 seconds))
  }

  it should "convert Blocking with millis" in {
    BlockingOrNot.typeConverter.convertTo(classOf[BlockingOrNot], Blocking(10 millis).toString) should be (Blocking(10 millis))
  }

}