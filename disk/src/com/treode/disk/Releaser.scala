package com.treode.disk

import com.treode.async.{Async, Callback}
import com.treode.async.implicits._

private class Releaser extends AbstractReleaser [SegmentPointer] {

  private def free (released: Seq [SegmentPointer]) {
    for ((disk, segs) <- released groupBy (_.disk))
      disk.free (segs)
  }

  def leave (epoch: Int): Unit =
    free (_leave (epoch))

  def release (segments: Seq [SegmentPointer]): Unit =
    free (_release (segments))

  def join [A] (cb: Callback [A]): Callback [A] = {
    val epoch = _join()
    cb.ensure (leave (epoch))
  }

  def join [A] (task: Async [A]): Async [A] =
    new Async [A] {
      def run (cb: Callback [A]): Unit = task.run (join (cb))
    }}
