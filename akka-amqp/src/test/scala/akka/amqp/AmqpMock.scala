package akka.amqp

import com.rabbitmq.client.{ Connection, Channel }
import org.mockito.Mockito._

trait AmqpMock {
  val channel = mock(classOf[Channel])
  val connection = mock(classOf[Connection])

  when(channel.getConnection).thenReturn(connection)
}
