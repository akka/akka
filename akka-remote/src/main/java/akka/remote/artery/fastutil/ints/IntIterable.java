/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.ints;

/*		 
 * Copyright (C) 2002-2016 Sebastiano Vigna
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */


import java.lang.Iterable;

/**
 * A type-specific {@link Iterable} that strengthens that specification of {@link Iterable#iterator()}.
 * <p>
 * <p><strong>Warning</strong>: Java will let you write &ldquo;colon&rdquo; <code>for</code> statements with primitive-type
 * loop variables; however, what is (unfortunately) really happening is that at each iteration an
 * unboxing (and, in the case of <code>fastutil</code> type-specific data structures, a boxing) will be performed. Watch out.
 *
 * @see Iterable
 */

public interface IntIterable extends Iterable<Integer> {

  /**
   * Returns a type-specific iterator.
   * <p>
   * Note that this specification strengthens the one given in {@link Iterable#iterator()}.
   *
   * @return a type-specific iterator.
   */
  IntIterator iterator();
}

