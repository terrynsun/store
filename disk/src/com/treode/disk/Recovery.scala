package com.treode.disk

import java.util.ArrayList
import scala.collection.JavaConversions._

import com.treode.async._
import com.treode.buffer.PagedBuffer
import com.treode.pickle.{TagRegistry, unpickle}

class Recovery (scheduler: Scheduler, disks: DisksKit) {

  private val recoveries = new TagRegistry [Callback [Unit] => Any]
  private val records = new RecordRegistry
  private var openers = new ArrayList [Recovery => Any]
  private var closers = new ArrayList [Runnable]

  def open [B] (desc: RootDescriptor [B]) (f: Recovery => Any): Unit = synchronized {
    require (openers != null, "Recovery has already begun.")
    openers.add (f)
  }

  def recover [B] (desc: RootDescriptor [B]) (f: (B, Callback [Unit]) => Any): Unit = {
    println (s"registered $desc")
    recoveries.register (desc.pblk, desc.id.id) (f.curried)
  }

  def replay [R] (desc: RecordDescriptor [R]) (f: R => Any): Unit =
    records.register (desc) (f)

  def onClose (f: Callback [Unit] => Any): Unit = synchronized {
    require (closers != null, "Recovery has already closed.")
    closers.add (toRunnable (f))
  }

  private def open() {
    val initializers = synchronized {
      val is = this.openers
      this.openers = null
      is
    }
    initializers foreach (_ (this))
  }

  private def close() {
    val closers = synchronized {
      val cs = this.closers
      this.closers = null
      cs
    }
    closers foreach (scheduler.execute (_))
  }

  def recover (meta: RootRegistry.Meta, cb: Callback [Unit]) {

    open()

    val logsReplayed = callback (cb) { _: Unit =>
      close()
    }

    val logsMerged = delay (cb) { iter: ReplayIterator =>
      AsyncIterator.foreach (iter, cb) { case ((time, replay), cb) =>
        guard (cb) (replay())
        cb()
      }}

    val rootsRecovered = delay (cb) { _: Unit =>
      disks.replayIterator (records, logsMerged)
    }

    val buf = PagedBuffer (12)

    val rootsRead = delay (cb) { _: Unit =>
      val roots = unpickle (DiskPicklers.seq (recoveries.unpickler), buf)
      val latch = Callback.latch (roots.size, rootsRecovered)
      roots foreach (_ (latch))
    }

    disks.fill (buf, meta.pos, rootsRead)
  }}
