package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.UUID


/**
 *
 */

class VoldemortStorageBackendSuite extends FunSuite with ShouldMatchers {

  test("UUID generation looks like"){
    System.out.println(UUID.newUuid.toString)    
  }
}