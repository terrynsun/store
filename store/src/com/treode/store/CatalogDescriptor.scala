package com.treode.store

import com.treode.pickle.Pickler

class CatalogDescriptor [C] (val id: CatalogId, val pcat: Pickler [C]) {

  def listen (f: C => Any): Unit =
    ???

  def issue (cat: C): Unit =
    ???

  override def toString = s"CatalogDescriptor($id,$pcat)"
}

object CatalogDescriptor {

  def apply [M] (id: CatalogId, pval: Pickler [M]): CatalogDescriptor [M] =
    new CatalogDescriptor (id, pval)
}