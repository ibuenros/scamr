package scamr.io

import java.io.IOException
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{PathFilter, FileStatus, FileSystem, Path}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTimeZone, DateTime}
import scala.collection.mutable

object InputOutputUtils {
  object AcceptAllPathsFilter extends PathFilter {
    override def accept(path: Path): Boolean = true
  }

  lazy val random = new scala.util.Random()

  // Generates a random working directory name using the current time, user name, job name, and a
  // random number as components.
  def randomWorkingDir(prefix: Path, jobName: String = ""): Path = {
    val now = DateTimeFormat.forPattern("YYYY-MM-dd-HH-mm-ss").print(new DateTime(System.currentTimeMillis,
      DateTimeZone.UTC))
    val randomLong = random.nextLong.abs.toString
    // Extract a sanitized job name component from the full job name by replacing all whitespace with underscores
    // and all non-word characters (a-zA-Z_0-9) with empty strings.
    val sanitizedName = jobName match {
      case "" | null => ""
      case _ => jobName.replaceAll("\\s+", "_").replaceAll("\\W+", "") + "-"
    }
    val userName = System.getenv("USER")
    val randomDirName = new Path(s"$userName-$now-$sanitizedName-$randomLong".toLowerCase)
    new Path(prefix, randomDirName)
  }

  // Variations of listRecursive
  def listRecursive(rootPath: Path, fs: FileSystem): Array[FileStatus] =
    listRecursive(rootPath, fs, AcceptAllPathsFilter, -1)

  def listRecursive(rootPath: Path, fs: FileSystem, filter: PathFilter): Array[FileStatus] =
    listRecursive(rootPath: Path, fs: FileSystem, filter, -1)

  def listRecursive(rootPath: Path, fs: FileSystem, maxDepth: Int): Array[FileStatus] =
    listRecursive(rootPath: Path, fs: FileSystem, AcceptAllPathsFilter, maxDepth)

  def listRecursive(rootPath: Path, fs: FileSystem, filter: PathFilter, maxDepth: Int): Array[FileStatus] = {
    var fileStati = mutable.Buffer[FileStatus]()
    if (!fs.getFileStatus(rootPath).isDirectory) {
      throw new IOException(s"Root path is not a directory: ${rootPath.toUri}")
    }
    var (nextDirs, nextFiles) = fs.listStatus(Array(fullyQualifiedPath(rootPath, fs)), filter).partition { _.isDirectory }

    var curDepth = 0
    while (nextDirs.nonEmpty) {
      fileStati ++= nextFiles
      fileStati ++= nextDirs
      curDepth += 1
      if (maxDepth < 0 || curDepth <= maxDepth) {
        val (nextDirs2, nextFiles2) = fs.listStatus(nextDirs.map { _.getPath }, filter).partition { _.isDirectory }
        nextDirs = nextDirs2
        nextFiles = nextFiles2
      } else {
        nextDirs = Array()
        nextFiles = Array()
      }
    }
    fileStati ++= nextFiles
    fileStati.toArray
  }

  // Converts the given possibly-relative Path into a fully-qualified Path.
  def fullyQualifiedPath(path: String, conf: Configuration): Path = fullyQualifiedPath(new Path(path), conf)

  def fullyQualifiedPath(path: Path, conf: Configuration): Path = fullyQualifiedPath(path, path.getFileSystem(conf))

  def fullyQualifiedPath(path: String, fs: FileSystem): Path = fullyQualifiedPath(new Path(path), fs)

  def fullyQualifiedPath(path: Path, fs: FileSystem): Path = path.makeQualified(fs.getUri, fs.getWorkingDirectory)
}
