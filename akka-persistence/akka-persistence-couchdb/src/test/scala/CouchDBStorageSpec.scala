package se.scalablesolutions.akka.persistence.couchdb

import org.specs._

object CouchDBStorageSpec extends Specification {
	val ENCODING = "UTF-8"
	
	println("\n\n\n\n")
	CouchDBStorageBackend.insertMapStorageEntryFor("weather", "abc".getBytes , "henbf".getBytes)
	//CouchDBStorageBackend.removeMapStorageFor("weather")
	CouchDBStorageBackend.insertMapStorageEntryFor("weather", "def".getBytes , "werg".getBytes)
	//CouchDBStorageBackend.removeMapStorageFor("weather" , "def".getBytes)
}