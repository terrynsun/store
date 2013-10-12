package com.treode.store.log

import java.util.ArrayList
import scala.collection.mutable.Builder

import com.treode.cluster.concurrent.Callback

private [store] class DiskStub (val maxBlockSize: Int) extends BlockWriter with BlockCache {

  private val blocks = new ArrayList [Block] ()

  def write (block: Block, cb: Callback [Long]) {
    val pos = blocks.size
    blocks.add (block)
    cb (pos)
  }

  def get (pos: Long, cb: Callback [Block]): Unit = {
    require (pos < Int.MaxValue)
    cb (blocks.get (pos.toInt))
  }

  def get (pos: Long): Block = {
    require (pos < Int.MaxValue)
    blocks.get (pos.toInt)
  }}
