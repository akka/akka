/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil.ints;

import java.util.ListIterator;

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

/**
 * A type-specific bidirectional iterator that is also a {@link ListIterator}.
 * <p>
 * <P>This interface merges the methods provided by a {@link ListIterator} and
 * a type-specific {@link akka.remote.artery.fastutil.BidirectionalIterator}. Moreover, it provides
 * type-specific versions of {@link java.util.ListIterator#add(Object) add()}
 * and {@link java.util.ListIterator#set(Object) set()}.
 *
 * @see java.util.ListIterator
 * @see akka.remote.artery.fastutil.BidirectionalIterator
 */

public interface IntListIterator extends ListIterator<Integer>, IntBidirectionalIterator {


  void set(int k);

  void add(int k);


}

