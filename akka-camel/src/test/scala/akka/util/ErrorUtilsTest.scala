package akka.util

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import akka.util.ErrorUtils._

class ErrorUtilsTest extends FlatSpec with ShouldMatchers{

  "tryAll" should  "make sure blocks after failed block get executed" in {
    class MyException extends Exception
    var doneBlock2 = false
    intercept[BlockException]{
      tryAll(
        throw new MyException,
        doneBlock2 = true,
        println("Another block is done")
      )
    }
    doneBlock2 should  be (true)
  }

  it should  "throw BlockException containing exceptions when some blocks fail" in {
    class MyException extends Exception
    val e1 = new MyException
    val e2 = new MyException
    try{
      tryAll(
        throw e1,
        println("Another block is done"),
        throw e2

      )
      fail("Expected BlockException to be thrown")
    }catch{
      case BlockException(errors) => errors should be (List(e1,e2))
    }
  }

  it should  "execute all blocks in order" in {
    var executed = List[String]()
    tryAll(
    {executed = "b1" :: executed},
    {executed = "b2" :: executed},
    {executed = "b3" :: executed}
    )
    executed should be (List("b3", "b2", "b1"))
  }
  
  "try_" should "return Left on failure" in {
    try_(throw new RuntimeException).isLeft should be (true)
  }

  "try_" should "return Right on success" in {
    try_(math.random).isRight should be (true)
  }

}