package org.tessellation.cli

import scala.concurrent.duration.DurationInt

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.ext.decline.decline._

import com.monovore.decline._
import eu.timepit.refined.auto._
import fs2.io.file.Path

object snapshot {

  val storedSnapshotPath: Opts[Path] = Opts
    .env[Path]("CL_SNAPSHOT_STORED_PATH", help = "Path to store created snapshot")
    .withDefault(Path("snapshot"))

  val opts = storedSnapshotPath.map(SnapshotConfig(2L, _, 10.seconds))
}