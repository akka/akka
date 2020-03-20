/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.source

// #sourceFutureSource

import akka.NotUsed
import akka.stream.scaladsl.Source

import scala.concurrent.Future

object FutureSource {
  def sourceCompletionStageSource(): Unit = {
    val userRepository: UserRepository = ??? // an abstraction over the remote service
    val userFutureSource = Source.futureSource(userRepository.loadUsers)
    // ...
  }

  trait UserRepository {
    def loadUsers: Future[Source[User, NotUsed]]
  }

  case class User()
}

// #sourceFutureSource
