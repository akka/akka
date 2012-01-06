package akka.camel

import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers

object MockitoFun1 extends App with MockitoSugar{

  trait X{
    def x
  }

  trait Y{
    def y : Int
  }

  val xy = mock[X with Y]
  when(xy.y) thenReturn 8

}

object MockitoFun2 extends App with MockitoSugar{

  trait X{
    def x
  }

  trait Y{ self: X =>
    def y(a:Int) : Int
  }
  
  class Z extends X with Y{
    def x = 9
    def y(a:Int) = 7
  }

  val xy = mock[Z]
  when(xy.y(Matchers.any[Int])) thenReturn 8

}