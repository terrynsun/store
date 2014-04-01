package com.treode.disk

import scala.util.Random

import com.treode.async.StubScheduler
import com.treode.async.io.StubFile
import com.treode.buffer.PagedBuffer
import org.scalatest.FlatSpec

import DiskTestTools._

class PageLedgerSpec extends FlatSpec {

  "PageLedger.write" should "reject oversized ledgers" in {

    implicit val random = new Random
    implicit val scheduler = StubScheduler.random (random)

    // Make a large ledger.
    val ledger = new PageLedger
    for (_ <- 0 until 256)
      ledger.add (random.nextInt, random.nextLong, random.nextGroup, 128)

    // Write something known to the file.
    val buf = PagedBuffer (12)
    for (i <- 0 until 1024)
      buf.writeInt (i)
    val file = new StubFile (1<<12)
    file.flush (buf, 0) .pass

    // Check that the write throws an exception.
    PageLedger.write (ledger, file, 0, 256) .fail [PageLedgerOverflowException]

    // Check that the file has not been overwritten.
    buf.clear()
    file.fill (buf, 0, 1024) .pass
    for (i <- 0 until 1024)
      assertResult (i) (buf.readInt())
  }}