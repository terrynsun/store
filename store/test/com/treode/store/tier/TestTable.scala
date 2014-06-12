package com.treode.store.tier

import com.treode.async.{Async, AsyncIterator}
import com.treode.async.stubs.StubScheduler
import com.treode.async.stubs.implicits._
import com.treode.disk.{Disk, ObjectId, PageHandler, RecordDescriptor}
import com.treode.store.{Bytes, Residents, StorePicklers, TxClock}

import Async.guard

private class TestTable (table: TierTable) (implicit disk: Disk)
extends PageHandler [Long] {

  def get (key: Int): Async [Option [Int]] = guard {
    for (cell <- table.get (Bytes (key), TxClock.MaxValue))
      yield cell.value.map (_.int)
  }

  def iterator: AsyncIterator [TestCell] =
    table.iterator (Residents.all) .map (new TestCell (_))

  def toSeq  (implicit scheduler: StubScheduler): Seq [(Int, Int)] =
    for (c <- iterator.toSeq; if c.value.isDefined)
      yield (c.key, c.value.get)

  def toMap (implicit scheduler: StubScheduler): Map [Int, Int] =
    toSeq.toMap

  def put (key: Int, value: Int): Async [Unit] = guard {
    val gen = table.put (Bytes (key), TxClock.MinValue, Bytes (value))
    TestTable.put.record (gen, key, value)
  }

  def delete (key: Int): Async [Unit] = guard {
    val gen = table.delete (Bytes (key), TxClock.MinValue)
    TestTable.delete.record (gen, key)
  }

  def probe (obj: ObjectId, groups: Set [Long]): Async [Set [Long]] = guard {
    table.probe (groups)
  }

  def compact (obj: ObjectId, groups: Set [Long]): Async [Unit] = guard {
    for {
      meta <- table.compact (groups, Residents.all)
      _ <- TestTable.checkpoint.record (meta)
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

    def launch (implicit launch: Disk.Launch): Async [TestTable]
  }

  val descriptor = {
    import StorePicklers._
    TierDescriptor (0x28) ((_, _, _) => true)
  }

  val put = {
    import StorePicklers._
    RecordDescriptor (0x09, tuple (ulong, int, int))
  }

  val delete = {
    import StorePicklers._
    RecordDescriptor (0x37, tuple (ulong, int))
  }

  val checkpoint = {
    import StorePicklers._
    RecordDescriptor (0xD1, tierMeta)
  }}
