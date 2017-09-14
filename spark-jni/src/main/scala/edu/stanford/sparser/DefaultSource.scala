/*
 * Copyright 2014 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.stanford.sparser

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.mapreduce.{Job, TaskAttemptContext}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.{CaseInsensitiveMap, CompressionCodecs}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.text.TextFileFormat
import org.apache.spark.sql.sources.{DataSourceRegister, Filter}
import org.apache.spark.sql.types.{StringType, StructType}
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal


private[sparser] class DefaultSource extends TextFileFormat with DataSourceRegister {

  override def equals(other: Any): Boolean = other match {
    case _: DefaultSource => true
    case _ => false
  }

  override def shortName(): String = "sparser"


  private def verifySchema(schema: StructType): Unit = {
    val tpe = schema(0).dataType
  }

  override def inferSchema(
                            sparkSession: SparkSession,
                            options: Map[String, String],
                            files: Seq[FileStatus]): Option[StructType] = Some(new StructType().add("value", StringType))

  override def prepareWrite(
                             sparkSession: SparkSession,
                             job: Job,
                             options: Map[String, String],
                             dataSchema: StructType): OutputWriterFactory = {
    verifySchema(dataSchema)

    val textOptions = new TextOptions(options)
    val conf = job.getConfiguration

    textOptions.compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }

    new OutputWriterFactory {
      override def newInstance(
                                path: String,
                                dataSchema: StructType,
                                context: TaskAttemptContext): OutputWriter = {
        new TextOutputWriter(path, dataSchema, context)
      }

      override def getFileExtension(context: TaskAttemptContext): String = {
        ".txt" + CodecStreams.getCompressionExtension(context)
      }
    }
  }

  override def buildReader(
                            sparkSession: SparkSession,
                            dataSchema: StructType,
                            partitionSchema: StructType,
                            requiredSchema: StructType,
                            filters: Seq[Filter],
                            options: Map[String, String],
                            hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {

    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    (file: PartitionedFile) => {
      println(file.filePath)
      // println("Start: " + file.start)
      // println("Length: " + file.length)
      val filtersStr = options("filters")
      println("Filters: " + filtersStr)
      val projectionsStr = options("projections")
      println("Projections: " + projectionsStr)
      println("Port: " + broadcastedHadoopConf.value.value.get("port"))
      val sp = new Sparser()
      sp.parseJson(file.filePath, file.start, file.length)
      sp.iterator()

      // val reader = new HadoopFileLinesReader(file, broadcastedHadoopConf.value.value)
      // println("after reader")
      // Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => reader.close()))

      // println("after foreach")
      // if (requiredSchema.isEmpty) {
      //   val emptyUnsafeRow = new UnsafeRow(0)
      //   reader.map(_ => emptyUnsafeRow)
      // } else {
      //   val unsafeRow = new UnsafeRow(1)
      //   val bufferHolder = new BufferHolder(unsafeRow)
      //   val unsafeRowWriter = new UnsafeRowWriter(bufferHolder, 1)

      //   reader.map { line =>
      //     // Writes to an UnsafeRow directly
      //     bufferHolder.reset()
      //     unsafeRowWriter.write(0, line.getBytes, 0, line.getLength)
      //     unsafeRow.setTotalSize(bufferHolder.totalSize())
      //     unsafeRow
      //   }
      // }
    }
  }
}

class TextOutputWriter(
                        path: String,
                        dataSchema: StructType,
                        context: TaskAttemptContext)
  extends OutputWriter {

  private val writer = CodecStreams.createOutputStream(context, new Path(path))

  override def write(row: InternalRow): Unit = {
    if (!row.isNullAt(0)) {
      val utf8string = row.getUTF8String(0)
      utf8string.writeTo(writer)
    }
    writer.write('\n')
  }

  override def close(): Unit = {
    writer.close()
  }
}

/**
  * Options for the Text data source.
  */
private[sparser] class TextOptions(@transient private val parameters: CaseInsensitiveMap[String])
  extends Serializable {

  import TextOptions._

  def this(parameters: Map[String, String]) = this(CaseInsensitiveMap(parameters))

  /**
    * Compression codec to use.
    */
  val compressionCodec = parameters.get(COMPRESSION).map(CompressionCodecs.getCodecClassName)
}

private[sparser] object TextOptions {
  val COMPRESSION = "compression"
}


private[sparser]
class SerializableConfiguration(@transient var value: Configuration) extends Serializable {
  @transient private[sparser] lazy val log = LoggerFactory.getLogger(getClass)

  /**
    * Execute a block of code that returns a value, re-throwing any non-fatal uncaught
    * exceptions as IOException. This is used when implementing Externalizable and Serializable's
    * read and write methods, since Java's serializer will not report non-IOExceptions properly;
    * see SPARK-4080 for more context.
    */
  def tryOrIOException[T](block: => T): T = {
    try {
      block
    } catch {
      case e: IOException =>
        log.error("Exception encountered", e)
        throw e
      case NonFatal(e) =>
        log.error("Exception encountered", e)
        throw new IOException(e)
    }
  }

  private def writeObject(out: ObjectOutputStream): Unit = tryOrIOException {
    out.defaultWriteObject()
    value.write(out)
  }

  private def readObject(in: ObjectInputStream): Unit = tryOrIOException {
    value = new Configuration(false)
    value.readFields(in)
  }
}