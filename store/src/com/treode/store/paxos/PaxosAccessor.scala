/*
 * Copyright 2014 Treode, Inc.
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

package com.treode.store.paxos

import com.treode.async.{Async, Callback}
import com.treode.pickle.Pickler
import com.treode.store.{Bytes, TxClock}

import Async.guard

private [store] trait PaxosAccessor [K, V] {

  def lead (key: K, time: TxClock, value: V) (implicit paxos: Paxos): Async [V]
  def propose (key: K, time: TxClock, value: V) (implicit paxos: Paxos): Async [V]
}

private [store] object PaxosAccessor {

  def apply [K, V] (pk: Pickler [K], pv: Pickler [V]): PaxosAccessor [K, V] =
    new PaxosAccessor [K, V] {

      def lead (key: K, time: TxClock, value: V) (implicit paxos: Paxos): Async [V] =
        guard {
          paxos.lead (Bytes (pk, key), time, Bytes (pv, value)) .map (_.unpickle (pv))
        }

      def propose (key: K, time: TxClock, value: V) (implicit paxos: Paxos): Async [V] =
        guard {
          paxos.propose (Bytes (pk, key), time, Bytes (pv, value)) .map (_.unpickle (pv))
        }}

  def apply(): PaxosAccessor [Bytes, Bytes] =
    new PaxosAccessor [Bytes, Bytes] {

      def lead (key: Bytes, time: TxClock, value: Bytes) (implicit paxos: Paxos): Async [Bytes] =
        paxos.lead (key, time, value)

      def propose (key: Bytes, time: TxClock, value: Bytes) (implicit paxos: Paxos): Async [Bytes] =
        paxos.propose (key, time, value)
  }

  def key [K] (pk: Pickler [K]): PaxosAccessor [K, Bytes] =
    new PaxosAccessor [K, Bytes] {

      def lead (key: K, time: TxClock, value: Bytes) (implicit paxos: Paxos): Async [Bytes] =
        guard {
          paxos.lead (Bytes (pk, key), time, value)
        }

      def propose (key: K, time: TxClock, value: Bytes) (implicit paxos: Paxos): Async [Bytes] =
        guard {
          paxos.propose (Bytes (pk, key), time, value)
        }}

  def value [V] (pv: Pickler [V]): PaxosAccessor [Bytes, V] =
    new PaxosAccessor [Bytes, V] {

      def lead (key: Bytes, time: TxClock, value: V) (implicit paxos: Paxos): Async [V] =
        guard {
          paxos.lead (key, time, Bytes (pv, value)) .map (_.unpickle (pv))
        }

      def propose (key: Bytes, time: TxClock, value: V) (implicit paxos: Paxos): Async [V] =
        guard {
          paxos.propose (key, time, Bytes (pv, value)) .map (_.unpickle (pv))
        }}}
