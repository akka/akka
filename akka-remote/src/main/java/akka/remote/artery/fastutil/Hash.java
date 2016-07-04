/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.fastutil;

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
 * Basic data for all hash-based classes.
 * <p>
 * <h2>Historical note</h2>
 * <p>
 * <p><strong>Warning:</strong> the following comments are here for historical reasons,
 * and apply just to the <em>double hash</em> classes that can be optionally generated.
 * The standard <code>fastutil</code> distribution since 6.1.0 uses linear-probing hash
 * tables, and tables are always sized as powers of two.
 * <p>
 * <p>The classes in <code>fastutil</code> are built around open-addressing hashing
 * implemented <em>via</em> double hashing. Following Knuth's suggestions in the third volume of <em>The Art of Computer
 * Programming</em>, we use for the table size a prime <var>p</var> such that
 * <var>p</var>-2 is also prime. In this way hashing is implemented with modulo <var>p</var>,
 * and secondary hashing with modulo <var>p</var>-2.
 * <p>
 * <p>Entries in a table can be in three states: {@link #FREE}, {@link #OCCUPIED} or {@link #REMOVED}.
 * The naive handling of removed entries requires that you search for a free entry as if they were occupied. However,
 * <code>fastutil</code> implements two useful optimizations, based on the following invariant:
 * <blockquote>
 * Let <var>i</var><sub>0</sub>, <var>i</var><sub>1</sub>, &hellip;, <var>i</var><sub><var>p</var>-1</sub> be
 * the permutation of the table indices induced by the key <var>k</var>, that is, <var>i</var><sub>0</sub> is the hash
 * of <var>k</var> and the following indices are obtained by adding (modulo <var>p</var>) the secondary hash plus one.
 * If there is a {@link #OCCUPIED} entry with key <var>k</var>, its index in the sequence above comes <em>before</em>
 * the indices of any {@link #REMOVED} entries with key <var>k</var>.
 * </blockquote>
 * <p>
 * <p>When we search for the key <var>k</var> we scan the entries in the
 * sequence <var>i</var><sub>0</sub>, <var>i</var><sub>1</sub>, &hellip;,
 * <var>i</var><sub><var>p</var>-1</sub> and stop when <var>k</var> is found,
 * when we finished the sequence or when we find a {@link #FREE} entry. Note
 * that the correctness of this procedure it is not completely trivial. Indeed,
 * when we stop at a {@link #REMOVED} entry with key <var>k</var> we must rely
 * on the invariant to be sure that no {@link #OCCUPIED} entry with the same
 * key can appear later. If we insert and remove frequently the same entries,
 * this optimization can be very effective (note, however, that when using
 * objects as keys or values deleted entries are set to a special fixed value to
 * optimize garbage collection).
 * <p>
 * <p>Moreover, during the probe we keep the index of the first {@link #REMOVED} entry we meet.
 * If we actually have to insert a new element, we use that
 * entry if we can, thus avoiding to pollute another {@link #FREE} entry. Since this position comes
 * <i>a fortiori</i> before any {@link #REMOVED} entries with the same key, we are also keeping the invariant true.
 */

public interface Hash {

  /**
   * The initial default size of a hash table.
   */
  final public int DEFAULT_INITIAL_SIZE = 16;
  /**
   * The default load factor of a hash table.
   */
  final public float DEFAULT_LOAD_FACTOR = .75f;
  /**
   * The load factor for a (usually small) table that is meant to be particularly fast.
   */
  final public float FAST_LOAD_FACTOR = .5f;
  /**
   * The load factor for a (usually very small) table that is meant to be extremely fast.
   */
  final public float VERY_FAST_LOAD_FACTOR = .25f;

  /**
   * A generic hash strategy.
   * <p>
   * <P>Custom hash structures (e.g., {@link
   * akka.remote.artery.fastutil.objects.ObjectOpenCustomHashSet}) allow to hash objects
   * using arbitrary functions, a typical example being that of {@linkplain
   * akka.remote.artery.fastutil.ints.IntArrays#HASH_STRATEGY arrays}. Of course,
   * one has to compare objects for equality consistently with the chosen
   * function. A <em>hash strategy</em>, thus, specifies an {@linkplain
   * #equals(Object, Object) equality method} and a {@linkplain
   * #hashCode(Object) hash function}, with the obvious property that
   * equal objects must have the same hash code.
   * <p>
   * <P>Note that the {@link #equals(Object, Object) equals()} method of a strategy must
   * be able to handle <code>null</code>, too.
   */

  public interface Strategy<K> {

    /**
     * Returns the hash code of the specified object with respect to this hash strategy.
     *
     * @param o an object (or <code>null</code>).
     * @return the hash code of the given object with respect to this hash strategy.
     */

    public int hashCode(K o);

    /**
     * Returns true if the given objects are equal with respect to this hash strategy.
     *
     * @param a an object (or <code>null</code>).
     * @param b another object (or <code>null</code>).
     * @return true if the two specified objects are equal with respect to this hash strategy.
     */
    public boolean equals(K a, K b);
  }

  /**
   * The default growth factor of a hash table.
   */
  final public int DEFAULT_GROWTH_FACTOR = 16;
  /**
   * The state of a free hash table entry.
   */
  final public byte FREE = 0;
  /**
   * The state of a occupied hash table entry.
   */
  final public byte OCCUPIED = -1;
  /**
   * The state of a hash table entry freed by a deletion.
   */
  final public byte REMOVED = 1;

  /**
   * A list of primes to be used as table sizes. The <var>i</var>-th element is
   * the largest prime <var>p</var> smaller than 2<sup>(<var>i</var>+28)/16</sup>
   * and such that <var>p</var>-2 is also prime (or 1, for the first few entries).
   */

  final public int PRIMES[] = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 5, 5, 5, 5, 5, 5, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 13, 13, 13, 13, 13, 13, 13, 13, 19, 19, 19, 19, 19,
    19, 19, 19, 19, 19, 19, 19, 31, 31, 31, 31, 31, 31, 31, 43, 43, 43, 43, 43,
    43, 43, 43, 61, 61, 61, 61, 61, 73, 73, 73, 73, 73, 73, 73, 103, 103, 109,
    109, 109, 109, 109, 139, 139, 151, 151, 151, 151, 181, 181, 193, 199, 199,
    199, 229, 241, 241, 241, 271, 283, 283, 313, 313, 313, 349, 349, 349, 349,
    421, 433, 463, 463, 463, 523, 523, 571, 601, 619, 661, 661, 661, 661, 661,
    823, 859, 883, 883, 883, 1021, 1063, 1093, 1153, 1153, 1231, 1321, 1321,
    1429, 1489, 1489, 1621, 1699, 1789, 1873, 1951, 2029, 2131, 2143, 2311,
    2383, 2383, 2593, 2731, 2803, 3001, 3121, 3259, 3391, 3583, 3673, 3919,
    4093, 4273, 4423, 4651, 4801, 5023, 5281, 5521, 5743, 5881, 6301, 6571,
    6871, 7129, 7489, 7759, 8089, 8539, 8863, 9283, 9721, 10141, 10531, 11071,
    11551, 12073, 12613, 13009, 13759, 14323, 14869, 15649, 16363, 17029,
    17839, 18541, 19471, 20233, 21193, 22159, 23059, 24181, 25171, 26263,
    27541, 28753, 30013, 31321, 32719, 34213, 35731, 37309, 38923, 40639,
    42463, 44281, 46309, 48313, 50461, 52711, 55051, 57529, 60091, 62299,
    65521, 68281, 71413, 74611, 77713, 81373, 84979, 88663, 92671, 96739,
    100801, 105529, 109849, 115021, 120079, 125509, 131011, 136861, 142873,
    149251, 155863, 162751, 169891, 177433, 185071, 193381, 202129, 211063,
    220021, 229981, 240349, 250969, 262111, 273643, 285841, 298411, 311713,
    325543, 339841, 355009, 370663, 386989, 404269, 422113, 440809, 460081,
    480463, 501829, 524221, 547399, 571603, 596929, 623353, 651019, 679909,
    709741, 741343, 774133, 808441, 844201, 881539, 920743, 961531, 1004119,
    1048573, 1094923, 1143283, 1193911, 1246963, 1302181, 1359733, 1420039,
    1482853, 1548541, 1616899, 1688413, 1763431, 1841293, 1922773, 2008081,
    2097133, 2189989, 2286883, 2388163, 2493853, 2604013, 2719669, 2840041,
    2965603, 3097123, 3234241, 3377191, 3526933, 3682363, 3845983, 4016041,
    4193803, 4379719, 4573873, 4776223, 4987891, 5208523, 5439223, 5680153,
    5931313, 6194191, 6468463, 6754879, 7053331, 7366069, 7692343, 8032639,
    8388451, 8759953, 9147661, 9552733, 9975193, 10417291, 10878619, 11360203,
    11863153, 12387841, 12936529, 13509343, 14107801, 14732413, 15384673,
    16065559, 16777141, 17519893, 18295633, 19105483, 19951231, 20834689,
    21757291, 22720591, 23726449, 24776953, 25873963, 27018853, 28215619,
    29464579, 30769093, 32131711, 33554011, 35039911, 36591211, 38211163,
    39903121, 41669479, 43514521, 45441199, 47452879, 49553941, 51747991,
    54039079, 56431513, 58930021, 61539091, 64263571, 67108669, 70079959,
    73182409, 76422793, 79806229, 83339383, 87029053, 90881083, 94906249,
    99108043, 103495879, 108077731, 112863013, 117860053, 123078019, 128526943,
    134217439, 140159911, 146365159, 152845393, 159612601, 166679173,
    174058849, 181765093, 189812341, 198216103, 206991601, 216156043,
    225726379, 235720159, 246156271, 257054491, 268435009, 280319203,
    292730833, 305691181, 319225021, 333358513, 348117151, 363529759,
    379624279, 396432481, 413983771, 432312511, 451452613, 471440161,
    492312523, 514109251, 536870839, 560640001, 585461743, 611382451,
    638450569, 666717199, 696235363, 727060069, 759249643, 792864871,
    827967631, 864625033, 902905501, 942880663, 984625531, 1028218189,
    1073741719, 1121280091, 1170923713, 1222764841, 1276901371, 1333434301,
    1392470281, 1454120779, 1518500173, 1585729993, 1655935399, 1729249999,
    1805811253, 1885761133, 1969251079, 2056437379, 2147482951};

}
