package com.treode.store.atomic

import scala.util.Random

import com.treode.store.StoreTestConfig
import com.treode.tags.{Intensive, Periodic}
import org.scalatest.FreeSpec

import AtomicTestTools._

class AtomicSequentialSpec extends FreeSpec with AtomicBehaviors {

  "The atomic implementation should" - {

    "recover from a crash when" - {

      for { (name, checkpoint) <- Seq (
          "not checkpointed at all"   -> 0.0,
          "checkpointed occasionally" -> 0.01,
          "checkpointed frequently"   -> 0.1)
      } s"$name and" - {

        for { (name, compaction) <- Seq (
            "not compacted at all"   -> 0.0,
            "compacted occasionally" -> 0.01,
            "compacted frequently"   -> 0.1)
            if checkpoint >= compaction
      } s"$name with" - {

        implicit val config = StoreTestConfig (
            checkpointProbability = checkpoint,
            compactionProbability = compaction)

        for { (name, (ntables, nkeys)) <- Seq (
            "few tables"   -> (3, 100),
            "many tables"  -> (30, 100))
        } s"$name with" - {

          for { (name, (nbatches, nwrites, nops)) <- Seq (
              "small batches with small writes" -> (7, 5, 3),
              "small batches with large writes" -> (7, 5, 20),
              "big batches with small writes"   -> (7, 35, 3))
              if !(ntables == 30 && nwrites == 35)
          } name taggedAs (Intensive, Periodic) in {

            forAllCrashes { implicit random =>
              crashAndRecover (nbatches, ntables, nkeys, nwrites, nops)
            }}}}}}

    "issue atomic writes with" - {

      implicit val config = StoreTestConfig()

      val init = { implicit random: Random =>
        issueAtomicWrites (7, 3, 100, 5, 3)
      }

      for1host (init)
      for3hosts (init)
      for8hosts (init)
      for3with1offline (init)
      for3with1crashing (init)
      for3with1rebooting (init)
      for3with1bouncing (init)
      for1to1 (init)
      for1to3 (init)
      for1to3with1bouncing (init)
      for3to1 (init)
      for3to1with1bouncing (init)
      for3replacing1 (init)
      for3replacing1withSourceBouncing (init)
      for3replacing1withTargetBouncing (init)
      for3replacing1withCommonBouncing (init)
      for3replacing2 (init)
      for3replacing2withSourceBouncing (init)
      for3replacing2withTargetBouncing (init)
      for3replacing2withCommonBouncing (init)
      for3to3 (init)
      for3to3withSourceBouncing (init)
      for3to3withTargetBouncing (init)
      for3to8 (init)
      for8to3 (init)
    }

    "scan the whole databse with" - {

      for { (name, nslices) <- Seq (
          "one slice" -> 1,
          "four slices" -> 4)
      } s"$name and" - {

        implicit val config = StoreTestConfig()

        val init = { implicit random: Random =>
            scanWholeDatabase (nslices)
        }

        for3to8 (init)
        for8to3 (init)
      }}}}
