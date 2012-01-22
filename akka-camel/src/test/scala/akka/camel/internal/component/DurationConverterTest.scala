package akka.camel.internal.component

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import akka.util.duration._
import akka.util.Duration

class DurationConverterTest extends FlatSpec with ShouldMatchers{
  import DurationTypeConverter._

  "DurationTypeConverter" should "convert '10 nanos'" in {
    convertTo(classOf[Duration], "10 nanos") should be (10 nanos)
  }
  
  it should  "do the roundtrip" in {
    convertTo(classOf[Duration], DurationTypeConverter.toString(10 seconds)) should be (10 seconds)
  }

  it should "throw if invalid format" in{
    intercept[Exception]{
      convertTo(classOf[Duration], "abc nanos") should be (10 nanos)
    }
  }

  it should "throw if doesn't end with nanos" in{
    intercept[Exception]{
      convertTo(classOf[Duration], "10233") should be (10 nanos)
    }
  }

}

