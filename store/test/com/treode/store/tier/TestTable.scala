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

package com.treode.store.tier

import com.treode.async.{Async, BatchIterator}
import com.treode.async.stubs.StubScheduler
import com.treode.disk.{Disk, DiskLaunch, ObjectId, PageHandler, RecordDescriptor}
import com.treode.store.{Bytes, Residents, StorePicklers, TableId, TxClock}

import Async.{guard, when}

/** Wrap the production `SynthTable` with something that's easier to handle in testing. */
private class TestTable (id: TableId, table: SynthTable) (implicit disk: Disk)
extends PageHandler {

  def get (key: Int): Async [Option [Int]] = guard {
    for (cell <- table.get (Bytes (key), TxClock.MaxValue))
      yield cell.value.map (_.int)
  }

  def iterator: BatchIterator [TestCell] =
    table.iterator (Residents.all) .map (new TestCell (_))

  def toSeq: Async [Seq [(Int, Int)]] =
    for (cs <- iterator.toSeq) yield
      for (c <- cs; if c.value.isDefined)
        yield (c.key, c.value.get)

  def toMap: Async [Map [Int, Int]] =
    toSeq map (_.toMap)

  def put (key: Int, value: Int): Async [Unit] = guard {
    val gen = table.put (Bytes (key), TxClock.MinValue, Bytes (value))
    TestTable.put.record (gen, key, value)
  }

  def delete (key: Int): Async [Unit] = guard {
    val gen = table.delete (Bytes (key), TxClock.MinValue)
    TestTable.delete.record (gen, key)
  }

  def probe (obj: ObjectId, gens: Set [Long]): Async [Set [Long]] = guard {
    table.probe (gens)
  }

  def compact (obj: ObjectId, gens: Set [Long]): Async [Unit] = guard {
    assert (obj.id == id.id)
    for {
      result <- table.compact (gens, Residents.all)
      _ <- when (result) { case (compaction, release) =>
        for (_ <- TestTable.compact.record (compaction))
          yield disk.release (release.desc, release.obj, release.gens)
      }
    } yield ()
  }

  def checkpoint(): Async [Unit] = guard {
    for {
      meta <- table.checkpoint (Residents.all)
      _ <- TestTable.checkpoint.record (meta)
    } yield ()
  }}

private object TestTable {

  trait Medic {

    def launch (implicit launch: DiskLaunch): Async [TestTable]
  }

  val descriptor = TierDescriptor (0x28) ((_, _, _) => true)

  val put = {
    import StorePicklers._
    RecordDescriptor (0x09, tuple (ulong, int, int))
  }

  val delete = {
    import StorePicklers._
    RecordDescriptor (0x37, tuple (ulong, int))
  }

  val compact = {
    import StorePicklers._
    RecordDescriptor (0x7D, tierCompaction)
  }

  val checkpoint = {
    import StorePicklers._
    RecordDescriptor (0xAD, tierCheckpoint)
  }}
