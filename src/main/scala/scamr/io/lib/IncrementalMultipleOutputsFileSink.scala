package scamr.io.lib

import java.io.IOException
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{PathFilter, FileSystem, Path}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.output.{MultipleOutputs, LazyOutputFormat, FileOutputFormat}
import scamr.io.InputOutput.Sink
import scamr.io.{InputOutputUtils, InputOutput}

/**
 * An output Sink for incrementally writing data to an output directory with subdirectories, using MultipleOutputs.
 * The current use case is incrementally adding data to a partitioned, external Hive table. The sink actually
 * writes the data to a temporary directory and then moves the files into the correct location if the job succeeds.
 * Guaranteeing uniqueness of file names is up to the job implementation, one possible implementation is to include a
 * job start timestamp and the hadoop job id in the last component of the file path given to MultipleOutputs.write().
 *
 * Note: When this Sink is used, attempting to write to the regular output with SimpleReducer.emit() will throw an
 * IOException.
 *
 * @param jobName - the MR job name. Used as part of the working directory name.
 * @param baseOutputDir - the base output directory
 * @param outputNamesToFormats - map from (outputId -> OutputFormat class)
 * @param km - implicit manifest, scalac will autodetect
 * @param vm - implicit manifest, scalac will autodetect
 * @tparam K - the key type
 * @tparam V - the value type
 */
class IncrementalMultipleOutputsFileSink[K, V](
    val jobName: String, baseOutputDir: Path,
    val outputNamesToFormats: Map[String, Class[_ <: FileOutputFormat[K, V]]])
    (implicit km: Manifest[K], vm: Manifest[V]) extends Sink[K, V] {

  def this(jobName: String, baseOutputDir: Path, outputName: String, outputFormat: Class[_ <: FileOutputFormat[K, V]])
          (implicit km: Manifest[K], vm: Manifest[V]) =
    this(jobName, baseOutputDir, Map(outputName -> outputFormat))(km, vm)

  InputOutput.mustBeWritable(km, "Key class")
  InputOutput.mustBeWritable(vm, "Value class")

  // Suppress the regular output. All outputs happen via MultipleOutputs.write()
  override val outputFormatClass = classOf[NoWritesAllowedOutputFormat[K, V]]

  private var conf: Configuration = _

  lazy val workingDirPrefix = InputOutputUtils.fullyQualifiedPath("tmp", conf)
  lazy val workingDir = InputOutputUtils.randomWorkingDir(workingDirPrefix, jobName)
  lazy val outputDir = InputOutputUtils.fullyQualifiedPath(baseOutputDir, conf)

  override def configureOutput(job: Job) {
    super.configureOutput(job)

    conf = job.getConfiguration
    val workingDirFs = FileSystem.get(workingDir.toUri, conf)
    val outputDirFs = FileSystem.get(outputDir.toUri, conf)

    require(workingDirPrefix.isAbsolute, "workingDirPrefix must be absolute!")
    require(outputDir.isAbsolute, "outputDir path must be absolute!")

    require(workingDirFs.getUri == outputDirFs.getUri,
      "working dir and output dir cannot be on different file systems!")

    FileOutputFormat.setOutputPath(job, workingDir)

    val keyClass = km.runtimeClass.asInstanceOf[Class[K]]
    val valueClass = vm.runtimeClass.asInstanceOf[Class[V]]

    // If all output format classes are the same, then we can wrap in a LazyOutputFormat and avoid creating
    // empty output files. If they are not all the same, then we have to use the output formats directly.
    val distinctFormatClasses = outputNamesToFormats.values.toSet
    if (distinctFormatClasses.size == 1) {
      LazyOutputFormat.setOutputFormatClass(job, distinctFormatClasses.head)
      outputNamesToFormats.keys.foreach {
        name => MultipleOutputs.addNamedOutput(job, name, classOf[LazyOutputFormat[K, V]], keyClass, valueClass)
      }
    } else {
      outputNamesToFormats.foreach {
        case (name, formatClass) =>
          MultipleOutputs.addNamedOutput(job, name, formatClass, keyClass, valueClass)
      }
    }
    MultipleOutputs.setCountersEnabled(job, false)
  }

  override def onOutputWritten(job: Job, success: Boolean) {
    val conf = job.getConfiguration
    val fs = FileSystem.get(workingDir.toUri, conf)

    if (!success) {
      if (!conf.getBoolean("scamr.always.keep.interstage.files", false)) {
        fs.delete(workingDir, true)
      }
    } else {
      val pathFilter = new PathFilter {
        // Filter out the empty _SUCCESS file and the unnecessary _logs directory
        def accept(path: Path): Boolean = path.getName != "_SUCCESS" && path.getName != "_logs"
      }
      // Find all source files and directories in workingDir.
      val (sourceDirs, sourceFiles) = InputOutputUtils.listRecursive(workingDir, fs, pathFilter).partition { _.isDirectory }

      // Find the unique parent directories of all leaf files in the output. We can use FileSystem.mkdirs() to
      // recursively create the same relative paths in the outputDir.
      val sourceDirToStatus = sourceDirs.map { status => (status.getPath, status) }.toMap
      val leafSourceDirs = sourceFiles.map { _.getPath.getParent }.filterNot { _.equals(workingDir) }.toSet

      // Create the output dirs
      leafSourceDirs.foreach { sourcePath =>
        val sourceFileStatus = sourceDirToStatus(sourcePath)
        val relativePath = new Path(workingDir.toUri.relativize(sourcePath.toUri))
        val destinationPath = new Path(outputDir, relativePath)
        if (!fs.mkdirs(destinationPath, sourceFileStatus.getPermission)) {
          throw new IOException(s"mkdir failed: ${destinationPath.toUri}")
        }
      }

      // Finally, move the temporary output files to the right location inside outputDir.
      // Unfortunately, this cannot be done atomically, so in theory the MR job controller could fail at this stage
      // and we would end up with partially-copied files == data corruption. There are ways to correctly handle this,
      // i.e. use ZK to keep list of MR job ids for which files have not been copied and clean the partially-moved
      // files if the move fails ... but we don't do that here, at least for now.
      sourceFiles.foreach { status =>
        val sourcePath = status.getPath
        val relativePath = new Path(workingDir.toUri.relativize(sourcePath.toUri))
        val destPath = new Path(outputDir, relativePath)
        if (!fs.rename(sourcePath, destPath)) {
          throw new IOException(s"move failed: ${sourcePath.toUri} -> ${destPath.toUri}")
        }
      }
      // Finally, delete the intermediate working directory
      fs.delete(workingDir, true)
    }
  }
}
