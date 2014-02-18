package com.treode.disk

import java.nio.file.Path
import java.util.concurrent.ExecutorService
import scala.collection.immutable.Queue
import scala.language.postfixOps

import com.treode.async.{Async, AsyncConversions, Callback, Fiber, Latch, Scheduler}
import com.treode.async.io.File

import Async.{async, guard, run}
import AsyncConversions._

private class DiskDrives (implicit
    val scheduler: Scheduler,
    val config: DisksConfig
) extends Disks {

  type AttachItem = (Path, File, DiskGeometry)
  type AttachRequest = (Seq [AttachItem], Callback [Unit])
  type DrainRequest = (Seq [Path], Callback [Unit])
  type DetachRequest = DiskDrive
  type CheckpointRequest = (Int, Position, Callback [Unit])

  val fiber = new Fiber (scheduler)
  val logd = new Dispatcher [PickledRecord] (scheduler)
  val paged = new Dispatcher [PickledPage] (scheduler)
  val checkpointer = new Checkpointer (this)
  val releaser = new Releaser
  val compactor = new Compactor (this)
  val cache = new PageCache (this)

  var disks = Map.empty [Int, DiskDrive]
  var attachreqs = Queue.empty [AttachRequest]
  var detachreqs = List.empty [DetachRequest]
  var drainreqs = Queue.empty [DrainRequest]
  var checkreqs = Option.empty [CheckpointRequest]
  var engaged = true

  var bootgen = 0
  var number = 0
  var rootgen = 0
  var rootpos = Position (0, 0, 0)

  def panic (t: Throwable) {
    throw t
  }

  val panic: Callback [Unit] =
    new Callback [Unit] {
      def pass (v: Unit): Unit = ()
      def fail (t: Throwable): Unit = panic (t)
    }

  private def reengage() {
    if (!attachreqs.isEmpty) {
      val (first, rest) = attachreqs.dequeue
      attachreqs = rest
      _attach (first)
    } else if (!detachreqs.isEmpty) {
      val all = detachreqs
      detachreqs = List.empty
      _detach (all)
    } else if (!drainreqs.isEmpty) {
      val (first, rest) = drainreqs.dequeue
      drainreqs = rest
      _drain (first)
    } else if (!checkreqs.isEmpty) {
      val first = checkreqs.get
      checkreqs = None
      _checkpoint (first)
    } else {
      engaged = false
    }}

  def launch (checkpoints: CheckpointRegistry, pages: PageRegistry): Unit =
    fiber.execute {
      checkpointer.launch (checkpoints)
      compactor.launch (pages)
      reengage()
    }

  private def leave [A] (cb: Callback [A]): Callback [A] =
    new Callback [A] {
      def pass (v: A): Unit = fiber.execute {
        reengage()
        scheduler.pass (cb, v)
      }
      def fail (t: Throwable): Unit = fiber.execute {
        reengage()
        scheduler.fail (cb, t)
      }}

  private def _attach (req: AttachRequest) {
    val items = req._1
    val cb = leave (req._2)
    engaged = true
    run (cb) {

      val priorDisks = disks.values
      val priorPaths = priorDisks.setBy (_.path)
      val newPaths = items.setBy (_._1)
      val bootgen = this.bootgen + 1
      val number = this.number + items.size
      val attached = priorPaths ++ newPaths
      val newBoot = BootBlock (bootgen, number, attached, rootgen, rootpos)

      if (newPaths exists (priorPaths contains _)) {
        val already = (newPaths -- priorPaths).toSeq.sorted
        throw new AlreadyAttachedException (already)
      }

      for {
        newDisks <- items.zipWithIndex.latch.seq { case ((path, file, geometry), i) =>
          DiskDrive.init (this.number + i, path, file, geometry, newBoot, this)
        }
        _ <- priorDisks.latch.unit (_.checkpoint (newBoot))

      } yield {
        disks ++= newDisks.mapBy (_.id)
        this.bootgen = bootgen
        this.number = number
      }}}

  def attach (items: Seq [(Path, File, DiskGeometry)]): Async [Unit] =
    fiber.async { cb =>
      require (!items.isEmpty, "Must list at least one file or device to attach.")
      if (engaged)
        attachreqs = attachreqs.enqueue (items, cb)
      else
        _attach (items, cb)
    }

  def attach (items: Seq [(Path, DiskGeometry)], exec: ExecutorService): Async [Unit] =
    guard {
      val files = items map (openFile (_, exec))
      attach (files)
    }

  private def _detach (items: List [DiskDrive]) {
    engaged = true
    run (panic) {

      val paths = items map (_.path)
      val disks = this.disks -- (items map (_.id))
      val bootgen = this.bootgen + 1
      val attached = disks.values.setBy (_.path)
      val newboot = BootBlock (bootgen, number, attached, rootgen, rootpos)

      for {
        _ <- disks.latch.unit (_._2.checkpoint (newboot))
      } yield {
        this.disks = disks
        this.bootgen = bootgen
        items foreach (_.detach())
        println ("Detached " + (paths mkString ","))
      }}}

  def detach (disk: DiskDrive) {
    fiber.execute {
      if (engaged)
        detachreqs ::= disk
      else
        _detach (List (disk))
    }}

  private def _drain (req: DrainRequest) {
    val (items, _cb) = req
    val cb = leave (_cb)
    engaged = true
    run (cb) {

      val byPath = disks.values.mapBy (_.path)
      if (!(items forall (byPath contains _))) {
        val unattached = (items.toSet -- byPath.keySet).toSeq.sorted
        throw new NotAttachedException (unattached)
      }

      if (items.size == disks.size) {
        throw new CannotDrainAllException
      }

      val draining = items map (byPath.apply _)
      for {
        segs <- draining.latch.seq (_.drain())
      } yield {
        checkpointer.checkpoint()
        compactor.drain (segs.iterator.flatten)
      }}}

  def drain (items: Seq [Path]): Async [Unit] =
    fiber.async { cb =>
      require (!items.isEmpty, "Must list at least one file or device to attach.")
      if (engaged)
        drainreqs = drainreqs.enqueue (items, cb)
      else
        _drain (items, cb)
    }

  def add (disks: Seq [DiskDrive]): Async [Unit] =
    fiber.supply {
      for (disk <- disks) {
        this.disks += disk.id -> disk
        disk.added()
      }}

  def mark(): Async [Unit] =
    fiber.flatten {
      disks.latch.unit (_._2.mark())
  }

  private def _checkpoint (req: CheckpointRequest) {
    val (rootgen, rootpos, _cb) = req
    val cb = leave (_cb)
    engaged = true
    fiber.defer (cb) {

      val attached = disks.values.map (_.path) .toSet
      val newBoot = BootBlock (bootgen, number, attached, rootgen, rootpos)

      val task = for {
        _ <- disks.latch.unit (_._2.checkpoint (newBoot))
      } yield {
        this.rootgen = rootgen
        this.rootpos = rootpos
      }
      task run (cb)
    }}

  def checkpoint (rootgen: Int, rootpos: Position): Async [Unit] =
    fiber.async { cb =>
      require (checkreqs.isEmpty, "A checkpoint is already in progress.")
      if (engaged)
        checkreqs = Some ((rootgen, rootpos, cb))
      else
        engaged = true
        _checkpoint (rootgen, rootpos, cb)
    }

  def cleanable(): Async [Iterator [SegmentPointer]] =  {
    fiber.flatten {
      for {
        segs <- disks.latch.seq (_._2.cleanable())
      } yield segs.iterator.flatten
    }}

  def join [A] (task: Async [A]): Async [A] =
    releaser.join (task)

  def record [R] (desc: RecordDescriptor [R], entry: R): Async [Unit] =
    async (cb => logd.send (PickledRecord (desc, entry, cb)))

  def write [G, P] (desc: PageDescriptor [G, P], group: G, page: P): Async [Position] =
    async (cb => paged.send (PickledPage (desc, group, page, cb)))

  def fetch [P] (desc: PageDescriptor [_, P], pos: Position): Async [P] =
    guard {
      DiskDrive.read (disks (pos.disk) .file, desc, pos)
    }

  def read [P] (desc: PageDescriptor [_, P], pos: Position): Async [P] =
    cache.read (desc, pos)
}