/*
 * Copyright 2016 Machinomy
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

package com.machinomy.crdt.state

import cats._

/** Grow-only counter. Could be incremented only.
  * `combine` operation takes the maximum count for each replica.
  * Value is the sum of all replicas.
  *
  * @tparam R Replica identifier
  * @tparam E Counter element, must behave like [[scala.math.Numeric]]
  * @example
  * {{{
  *   import com.machinomy.crdt.state.GCounter
  *   import cats.syntax.all._
  *   import cats._
  *
  *   val counter = Monoid[GCounter[Int, Int]].empty
  *   val firstReplica = counter + (1 -> 1)
  *   val secondReplica = counter + (2 -> 2)
  *   val firstReplicaCombined = firstReplica |+| secondReplica
  *   val secondReplicaCombined = secondReplica |+| firstReplica
  *
  *   firstReplicaCombined == secondReplicaCombined
  * }}}
  * @see [[com.machinomy.crdt.state.GCounter.monoid]] Behaves like a [[cats.kernel.Monoid]]
  * @see [[com.machinomy.crdt.state.GCounter.partialOrder]] Behaves like a [[cats.kernel.PartialOrder]]
  * @see Shapiro, M., Preguiça, N., Baquero, C., & Zawirski, M. (2011).
  *      Conflict-free replicated data types.
  *      In Proceedings of the 13th international conference on Stabilization, safety, and security of distributed systems (pp. 386–400).
  *      Grenoble, France: Springer-Verlag.
  *      Retrieved from [[http://dl.acm.org/citation.cfm?id=2050642]]
  */
case class GCounter[R, E](state: Map[R, E])(implicit num: Numeric[E]) extends Convergent[E, E] {
  type Self = GCounter[R, E]

  /** Increment value for replica `pair._1` by `pair._2`. Only positive values are allowed.
    *
    * @see [[increment]]
    * @param pair Replica identifier
    * @return Updated GCounter
    */
  def +(pair: (R, E)): Self = increment(pair._1, pair._2)

  /** Increment value for replica `replicaId` by `delta`. Only positive values are allowed.
    *
    * @see [[+]]
    * @param replicaId Replica identifier.
    * @param delta     Increment of a counter
    * @return Updated GCounter
    */
  def increment(replicaId: R, delta: E): Self = {
    require(num.gteq(delta, num.zero), "Can only increment GCounter")
    if (num.equiv(delta, num.zero)) {
      this
    } else {
      state.get(replicaId) match {
        case Some(value) => new GCounter[R, E](state.updated(replicaId, num.plus(value, delta)))
        case None => new GCounter[R, E](state.updated(replicaId, delta))
      }
    }
  }

  /** Value for `replicaId`, or zero if absent.
    *
    * @param replicaId Replica identifier
    * @return
    */
  def get(replicaId: R): E = state.getOrElse(replicaId, num.zero)

  /** @return Value of the counter.
    */
  override def value: E = state.values.sum
}

object GCounter {
  /** Implements [[cats.kernel.Monoid]] type class for [[GCounter]].
    *
    * @tparam R Replica identifier
    * @tparam E Counter element, must behave like [[scala.math.Numeric]]
    */
  implicit def monoid[R, E](implicit num: Numeric[E]) = new Monoid[GCounter[R, E]] {
    override def empty: GCounter[R, E] = new GCounter[R, E](Map.empty[R, E])

    override def combine(x: GCounter[R, E], y: GCounter[R, E]): GCounter[R, E] = {
      def fill(ids: Set[R], a: Map[R, E], b: Map[R, E], result: Map[R, E] = Map.empty): Map[R, E] =
        if (ids.isEmpty) {
          result
        } else {
          val key = ids.head
          val valueA = a.getOrElse(key, num.zero)
          val valueB = b.getOrElse(key, num.zero)
          fill(ids.tail, a, b, result.updated(key, num.max(valueA, valueB)))
        }
      val ids = x.state.keySet ++ y.state.keySet
      GCounter(fill(ids, x.state, y.state))
    }
  }

  /** Implements [[cats.PartialOrder]] type class for [[GCounter]].
    *
    * @tparam R Replica identifier
    * @tparam E Counter element, must behave like [[scala.math.Numeric]]
    */
  implicit def partialOrder[R, E](implicit num: Numeric[E]) = PartialOrder.byLteqv[GCounter[R, E]] { (x, y) =>
    val ids = x.state.keySet ++ y.state.keySet
    ids.forall { id =>
      val xValue = x.state.getOrElse(id, num.zero)
      val yValue = y.state.getOrElse(id, num.zero)
      num.lteq(xValue, yValue)
    }
  }

  def apply[R, E: Numeric](): GCounter[R, E] = new GCounter[R, E](Map.empty[R, E])
}
