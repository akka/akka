/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.flow

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source

class StatefulMapConcat {

  implicit val system: ActorSystem = ???

  def zipWithIndex(): Unit = {
    // #zip-with-index
    val letterAndIndex = Source("a" :: "b" :: "c" :: "d" :: Nil).statefulMapConcat { () =>
      var counter = 0L

      // we return the function that will be invoked for each element
      { element =>
        counter += 1
        // we return an iterable with the single element
        (element, counter) :: Nil
      }
    }

    letterAndIndex.runForeach(println)
    // prints
    // (a,1)
    // (b,2)
    // (c,3)
    // (d,4)
    // #zip-with-index
  }

  def blacklist(): Unit = {
    // #blacklist
    val fruitsAndBlacklistCommands = Source(
      "banana" :: "pear" :: "orange" :: "blacklist:banana" :: "banana" :: "pear" :: "banana" :: Nil)

    val blacklistingFlow = Flow[String].statefulMapConcat { () =>
      var blacklist = Set.empty[String]

      { element =>
        if (element.startsWith("blacklist:")) {
          blacklist += element.drop(10)
          Nil // no element downstream when adding a blacklisted keyword
        } else if (blacklist(element)) {
          Nil // no element downstream if element is blacklisted
        } else {
          element :: Nil
        }
      }
    }

    fruitsAndBlacklistCommands.via(blacklistingFlow).runForeach(println)
    // prints
    // banana
    // pear
    // orange
    // pear
    // #blacklist
  }

  def reactOnEnd(): Unit = {
    // #bs-last
    val words = Source("baboon" :: "crocodile" :: "bat" :: "flamingo" :: "hedgehog" :: "beaver" :: Nil)

    val bWordsLast = Flow[String].concat(Source.single("-end-")).statefulMapConcat { () =>
      var stashedBWords: List[String] = Nil

      { element =>
        if (element.startsWith("b")) {
          // prepend to stash and emit no element
          stashedBWords = element :: stashedBWords
          Nil
        } else if (element.equals("-end-")) {
          // return in the stashed words in the order they got stashed
          stashedBWords.reverse
        } else {
          // emit the element as is
          element :: Nil
        }
      }
    }

    words.via(bWordsLast).runForeach(println)
    // prints
    // crocodile
    // flamingo
    // hedgehog
    // baboon
    // bat
    // beaver
    // #bs-last
  }

}
