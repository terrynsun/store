package com.treode.disk

import scala.collection.immutable.Queue

import com.treode.async.{Async, Callback, Fiber}
import com.treode.async.implicits._

import Async.guard
import Callback.{fanout, ignore}

private class Checkpointer (kit: DiskKit) {
  import kit.{config, drives, scheduler}

  val fiber = new Fiber
  var checkpoints: CheckpointRegistry = null
  var bytes = 0
  var entries = 0
  var checkreqs = List.empty [Callback [Unit]]
  var engaged = true

  private def reengage() {
    fanout (checkreqs) .pass()
    checkreqs = List.empty
    bytes = 0
    entries = 0
    engaged = false
  }

  private def _checkpoint() {
    guard {
      engaged = true
      for {
        marks <- drives.mark()
        _ <- checkpoints.checkpoint()
        _ <- drives.checkpoint (marks)
      } yield fiber.execute {
        reengage()
      }
    } .run (ignore)
  }

  def launch (checkpoints: CheckpointRegistry): Async [Unit] =
    fiber.supply {
      this.checkpoints = checkpoints
      if (!checkreqs.isEmpty || config.checkpoint (bytes, entries))
        _checkpoint()
      else
        engaged = false
    }

  def checkpoint(): Async [Unit] =
    fiber.async { cb =>
      checkreqs ::= cb
      if (!engaged)
        _checkpoint()
    }

  def tally (bytes: Int, entries: Int): Unit =
    fiber.execute {
      this.bytes += bytes
      this.entries += entries
      if (!engaged && checkreqs.isEmpty && config.checkpoint (this.bytes, this.entries))
        _checkpoint()
    }}
