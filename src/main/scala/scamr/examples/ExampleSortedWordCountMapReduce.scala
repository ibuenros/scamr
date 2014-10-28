package scamr.examples

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{NullWritable, LongWritable, Text}
import org.apache.hadoop.mapreduce.{ReduceContext, MapContext}
import scamr.MapReduceMain
import scamr.conf.{OnJobCompletion, LambdaJobModifier, ConfigureSpeculativeExecution}
import scamr.io.InputOutput
import scamr.io.tuples.Tuple2WritableComparable
import scamr.mapreduce.mapper.SimpleMapper
import scamr.mapreduce.reducer.SimpleReducer
import scamr.mapreduce.{MapReducePipeline, MapReduceJob}


class LongAndTextWritableComparable(tuple: (LongWritable, Text))
extends Tuple2WritableComparable[LongWritable, Text](tuple) {
  def this(a1: LongWritable, a2: Text) = this((a1, a2))
  def this() = this((new LongWritable, new Text))
}

class CombineCountAndWordIntoTupleMapper(context: MapContext[_, _, _, _])
extends SimpleMapper[Text, LongWritable, LongAndTextWritableComparable, NullWritable](context) {
  override def map(word: Text, count: LongWritable): Unit =
    emit(new LongAndTextWritableComparable(count, word), NullWritable.get)
}

class OutputSortedCountsReducer(context: ReduceContext[_, _, _, _])
extends SimpleReducer[LongAndTextWritableComparable, NullWritable, Text, LongWritable](context) {
  override def reduce(key: LongAndTextWritableComparable, ignored: Iterator[NullWritable]): Unit = emit(key._2, key._1)
}

// This example demonstrates a 2-stage MR pipeline which is like word count, but sorts the words according to the
// frequency first, and alphabetically within the same frequency.
// Sorted word count example. Stage 1 computes the counts.
// Stage 2 mapper combines the count and word into a tuple, sorted by count first and word 2nd. This
//   gives us the sort order we want on the reducer for free.
// Stage 2 reducer breaks the tuple back into a word and count and outputs them.
object ExampleSortedWordCountMapReduce extends MapReduceMain {
  override def run(conf: Configuration, args: Array[String]): Int = {
    val inputDirs = args.init
    val outputDir = args.last
    val logJobResultCallback = OnJobCompletion {
      case (job, Right(true)) => logger.info(s"MR Job ${job.getJobName} succeeded")
      case (job, _) => logger.info(s"MR Job ${job.getJobName} failed")
    }

    val pipeline = MapReducePipeline.init(conf) -->  // hint: start by adding a data source with -->
      new InputOutput.TextFileSource(inputDirs) --> // hint: use --> to direct data into or out of a stage
      new MapReduceJob(classOf[WordCountMapper], classOf[WordCountReducer], classOf[WordCountReducer],
        "ScaMR sorted word count example, stage 1") ++
      // hint: use ++ to add ConfModifiers or JobModifiers to a TaskStage or a StandAloneJob
      ConfigureSpeculativeExecution(false, false) ++ logJobResultCallback ++
      LambdaJobModifier { _.setNumReduceTasks(1) } --> // hint: use --> to chain MR jobs into pipelines
      new MapReduceJob(classOf[CombineCountAndWordIntoTupleMapper], classOf[OutputSortedCountsReducer],
        "ScaMR sorted word count example, stage 2") ++ logJobResultCallback -->
      new InputOutput.TextFileSink[Text, LongWritable](outputDir)
    if (pipeline.execute) 0 else 1
  }
}
