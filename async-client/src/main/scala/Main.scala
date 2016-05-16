import org.asynchttpclient._
import java.util.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await

object Run extends App {

  val asyncHttpClient = new DefaultAsyncHttpClient();


  val t = System.currentTimeMillis
  asyncHttpClient.prepareGet("http://www.example.com/").execute().get();
  println("(System.currentTimeMillis() - t) = " + (System.currentTimeMillis() - t) + "ms")
  
  val t1 = System.currentTimeMillis
  asyncHttpClient.prepareGet("http://www.example.com/").execute().get();
  println("(System.currentTimeMillis() - t) = " + (System.currentTimeMillis() - t1) + "ms")
  
  val t2 = System.currentTimeMillis
  asyncHttpClient.prepareGet("http://www.example.com/").execute().get();
  println("(System.currentTimeMillis() - t) = " + (System.currentTimeMillis() - t2) + "ms")
}
