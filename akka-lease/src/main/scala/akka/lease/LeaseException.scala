package akka.lease

import akka.annotation.ApiMayChange

@ApiMayChange
class LeaseException(message: String) extends RuntimeException(message)

@ApiMayChange
class LeaseTimeoutException(message: String) extends LeaseException(message)

