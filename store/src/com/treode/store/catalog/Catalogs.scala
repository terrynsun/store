package com.treode.store.catalog

import scala.util.Random

import com.treode.async.{Async, Scheduler}
import com.treode.cluster.Cluster
import com.treode.disk.Disk
import com.treode.store.{CatalogDescriptor, Library, StoreConfig}

private [store] trait Catalogs {

  def listen [C] (desc: CatalogDescriptor [C]) (f: C => Any)

  def issue [C] (desc: CatalogDescriptor [C]) (version: Int, cat: C): Async [Unit]
}

private [store] object Catalogs {

  trait Recovery {

    def launch (implicit disks: Disk.Launch): Async [Catalogs]
  }

  def recover () (implicit
      random: Random,
      scheduler: Scheduler,
      cluster: Cluster,
      library: Library,
      recovery: Disk.Recovery,
      config: StoreConfig
  ): Recovery =
    new RecoveryKit
}
