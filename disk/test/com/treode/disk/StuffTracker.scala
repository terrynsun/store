package com.treode.disk

import scala.util.Random

import com.treode.async.{Async, Scheduler}
import com.treode.async.implicits._
import com.treode.async.stubs.implicits._
import com.treode.async.stubs.StubScheduler

import Async.{guard, supply}
import DiskTestTools._
import Stuff.pager

class StuffTracker (implicit random: Random) {

  private var written = Map.empty [Long, Position]
  private var _probed = false
  private var _compacted = false
  private var _maximum = 0L

  def probed = _probed
  def compacted = _compacted
  def maximum = _maximum

  def write() (implicit disks: Disk): Async [Unit] = {
    var seed = random.nextLong()
    while (written contains seed)
      seed = random.nextLong()
    for {
      pos <- pager.write (0, seed, Stuff (seed))
    } yield {
      if (pos.offset > _maximum)
        _maximum = pos.offset
      written += seed -> pos
    }}

 def batch (nbatches: Int, nwrites: Int) (implicit
    scheduler: Scheduler, disks: Disk): Async [Unit] =
  for {
    _ <- (0 until nbatches) .async
    _ <- (0 until nwrites) .latch.unit
  } {
    write()
  }

  def attach () (implicit scheduler: Scheduler, launch: Disk.Launch) {
    import launch.disks

    _probed = false
    _compacted = false

    pager.handle (new PageHandler [Long] {

      def probe (obj: ObjectId, groups: Set [Long]): Async [Set [Long]] =
        supply {
          _probed = true
          val (keep, remove) = groups partition (_ => random.nextInt (3) == 0)
          written --= remove
          keep
        }

      def compact (obj: ObjectId, groups: Set [Long]): Async [Unit] = {
        guard {
          _compacted = true
          for (seed <- groups.latch.unit)
            for (pos <- pager.write (0, seed, Stuff (seed)))
              yield written += seed -> pos
        }}})
  }

  def check () (implicit scheduler: StubScheduler, disks: Disk) {
    for ((seed, pos) <- written) {
      if (disks.isInstanceOf [DiskAgent])
        pager.assertInLedger (pos, 0, seed)
      pager.read (pos) .expect (Stuff (seed))
    }}}
